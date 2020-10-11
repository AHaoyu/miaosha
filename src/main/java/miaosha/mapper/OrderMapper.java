package miaosha.mapper;

import miaosha.pojo.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper {
    void createOrder(Order order);
}
