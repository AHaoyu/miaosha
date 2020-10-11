package miaosha.service;

import miaosha.Utils.CacheKey;
import miaosha.mapper.StockMapper;
import miaosha.pojo.Stock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.*;
import java.util.concurrent.TimeUnit;

@Service
public class StockService {

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    public Stock getStockById(int id) {
        Stock s = stockMapper.getStockById(id);
        System.out.println(s.toString());
        return s;
    }

    public void updateStockById(Stock stock) {
        stockMapper.updateStockById(stock);
    }

    public int updateByOptimistic(Stock stock) {
        return stockMapper.updateByOptimistic(stock);
    }

    public Stock getStockInfoForUpdate(int sid) {
        return stockMapper.getStockInfoForUpdate(sid);
    }

    public String getStockCountByCache(int sid) {
        String hashKey = CacheKey.STOCK_COUNT.getKey() + "_" + sid;
        return stringRedisTemplate.opsForValue().get(hashKey);
    }

    public void setStockCountToCache(int sid, String count) {
        String hashKey = CacheKey.STOCK_COUNT.getKey() + "_" + sid;
        stringRedisTemplate.opsForValue().set(hashKey, count, 3600, TimeUnit.SECONDS);
    }

    public void delStockCountCache(int sid) {
        String hashKey = CacheKey.STOCK_COUNT.getKey() + "_" + sid;
        stringRedisTemplate.delete(hashKey);
    }
}
