package service

// CreateOrder [功能概述] 创建新订单并触发后续异步处理流程。
// [业务背景] 该函数是下单流程的核心入口，负责将用户选购的商品转化为待处理的订单记录。
// [输入输出]
//   - uid: 用户唯一标识，用于校验用户合法性。
//   - pid: 商品唯一标识，用于获取实时单价。
//   - 返回: 创建成功的订单对象或错误信息。
func (s *OrderService) CreateOrder(uid int64, pid int64) (*Order, error) {
    // [逻辑说明] 校验用户状态，确保下单主体存在。
    user, err := s.userRepo.Find(uid)
    if err != nil {
        return nil, err
    }
    
    // [逻辑说明] 获取商品信息。此处坚持从服务端数据库获取价格，以防前端参数篡改。
    product, err := s.prodRepo.Find(pid)
    if err != nil {
        return nil, err
    }

    // [逻辑说明] 初始化订单实体。
    order := &Order{
        UserID: uid,
        ProductID: pid,
        Price: product.Price, 
    }

    // [外部集成] 将订单创建事件发布至消息队列（RabbitMQ）。
    // [设计初衷] 通过异步解耦，下单主路径不阻塞等待库存扣减和邮件通知结果，提升系统响应速度。
    // 参考文档：异步下单流程设计：docs/arch/async-order.md
    err = s.mq.Publish("order_created", order)
    if err != nil {
        // [容错处理] 仅记录日志，防止 MQ 抖动导致用户下单失败。
        // 注意：这可能导致短暂的数据不一致，需配合后台补偿脚本。
        log.Printf("mq publish err: %v", err)
    }

    // [持久化] 保存订单记录。
    return s.orderRepo.Save(order)
}
