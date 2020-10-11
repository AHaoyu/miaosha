package miaosha.mapper;

import miaosha.pojo.Stock;
import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface StockMapper {

    Stock getStockById(int id);

    void updateStockById(Stock stock);

    int updateByOptimistic(Stock record);

    Stock getStockInfoForUpdate(int sid);
}
