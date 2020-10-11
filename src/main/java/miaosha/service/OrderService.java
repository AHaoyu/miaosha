package miaosha.service;

import miaosha.Utils.CacheKey;
import miaosha.mapper.OrderMapper;
import miaosha.mapper.StockMapper;
import miaosha.mapper.UserMapper;
import miaosha.pojo.Order;
import miaosha.pojo.Stock;
import miaosha.pojo.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;


@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StockService stockService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderService.class);


    public int createOptimisticOrder(int sid) throws Exception {
        //校验库存
        Stock stock = checkStock(sid);

        saleStock(stock);
        //创建订单
        createOrder(stock);
        return stock.getCount() - stock.getSale() - 1;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public int createPessimisticOrder(int sid) throws Exception {
        //校验库存
        Stock stock = checkStockForUpdate(sid);

        saleStock02(stock);
        //创建订单
        createOrder(stock);

        return stock.getCount() - stock.getSale();
    }

    private Stock checkStock(int sid) {
        Stock stock = stockMapper.getStockById(sid);
        if (stock.getSale().equals(stock.getCount())) {
            throw new RuntimeException("库存不足");
        }
        return stock;
    }

    private Stock checkStockForUpdate(int sid) {
        Stock stock = stockMapper.getStockInfoForUpdate(sid);
        if (stock.getSale().equals(stock.getCount())) {
            throw new RuntimeException("库存不足");
        }
        return stock;
    }

    private void saleStock(Stock stock) {
        LOGGER.info("查询数据库，尝试更新库存");
        int code = stockMapper.updateByOptimistic(stock);
        //System.out.println("+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++" + code);
        if (code == 0){
            throw new RuntimeException("并发更新库存失败，version不匹配") ;
        }
    }

    private void saleStock02(Stock stock) {
        stock.setSale(stock.getSale() + 1);
        stockMapper.updateStockById(stock);
    }

    private void createOrder(Stock stock) {
        Order order = new Order();
        order.setSid(stock.getId());
        order.setName(stock.getName());
        order.setUser_id(1);
        orderMapper.createOrder(order);
    }


    public int createVerifiedOrder(Integer sid, Integer userId, String verifyHash) throws Exception {
        LOGGER.info("请自行验证是否在抢购时间内,假设此处验证成功");

        // 验证hash值合法性
        String hashKey = CacheKey.HASH_KEY.getKey() + "_" + sid + "_" + userId;
        String verifyHashInRedis = stringRedisTemplate.opsForValue().get(hashKey);
        if (!verifyHash.equals(verifyHashInRedis)) {
            throw new Exception("hash值与Redis中不符合");
        }
        LOGGER.info("验证hash值合法性成功");

        // 检查用户合法性
        User user = userMapper.selectByPrimaryKey(userId.longValue());
        if (user == null) {
            throw new Exception("用户不存在");
        }
        LOGGER.info("用户信息验证成功：[{}]", user.toString());

        // 检查商品合法性
        Stock stock = stockMapper.getStockById(sid);
        if (stock == null) {
            throw new Exception("商品不存在");
        }
        LOGGER.info("商品信息验证成功：[{}]", stock.toString());

        //乐观锁更新库存
        saleStock(stock);
        //创建订单
        createOrderWithUserInfo(stock, userId);
        LOGGER.info("创建订单成功");

        return stock.getCount() - (stock.getSale()+1);
    }

    public void createOrderWithUserInfo(Stock stock, Integer userId) {
        Order order = new Order();
        order.setSid(stock.getId());
        order.setName(stock.getName());
        order.setUser_id(userId);
        orderMapper.createOrder(order);
    }


    public Boolean checkUserInfoInCache(int sid, int userId) {
        String key = CacheKey.USER_HAS_ORDER.getKey() + "_" + sid;
        LOGGER.info("检查用户Id：[{}] 是否抢购过商品Id：[{}] 检查Key：[{}]", userId, sid, key);
        return stringRedisTemplate.opsForSet().isMember(key, String.valueOf(userId));

    }

    public void createOrderWithMq(Integer sid, Integer userId) throws Exception {
        Thread.sleep(10000);
        Stock stock;
        //校验库存（不要学我在trycatch中做逻辑处理，这样是不优雅的。这里这样处理是为了兼容之前的秒杀系统文章）
        try {
            stock = checkStock(sid);
        } catch (Exception e) {
            LOGGER.info("库存不足！");
            return;
        }
        saleStock(stock);
        LOGGER.info("删除库存缓存");
        stockService.delStockCountCache(sid);
        LOGGER.info("写入订单至数据库");
        createOrderWithUserInfo(stock, userId);
        LOGGER.info("写入订单至缓存供查询");
        createOrderWithUserInfoInCache(stock, userId);
        LOGGER.info("下单完成");
    }

    private void createOrderWithUserInfoInCache(Stock stock, Integer userId) {
        String key = CacheKey.USER_HAS_ORDER.getKey() + "_" + stock.getId().toString();
        LOGGER.info("写入用户订单数据Set：[{}] [{}]", key, userId.toString());
        stringRedisTemplate.opsForSet().add(key, userId.toString());
    }
}
