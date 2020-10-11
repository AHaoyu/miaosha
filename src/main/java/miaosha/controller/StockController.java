package miaosha.controller;

import com.google.common.util.concurrent.RateLimiter;
import miaosha.pojo.Stock;
import miaosha.service.OrderService;
import miaosha.service.StockService;
import miaosha.service.UserService;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import org.springframework.amqp.core.AmqpTemplate;

import java.util.concurrent.TimeUnit;

@RestController
public class StockController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StockController.class);
    private static ExecutorService cachedThreadPool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    private static final int DELAY_MILLSECONDS = 1000;
    @Autowired
    private StockService stockService;

    @Autowired
    private OrderService orderService;

    @Autowired
    UserService userService;

    @Autowired
    private AmqpTemplate amqpTemplate;

    RateLimiter rateLimiter = RateLimiter.create(10);

    private class delCacheByThread implements Runnable {
        private int sid;
        public delCacheByThread(int sid) {
            this.sid = sid;
        }
        public void run() {
            try {
                LOGGER.info("异步执行缓存再删除，商品id：[{}]， 首先休眠：[{}] 毫秒", sid, DELAY_MILLSECONDS);
                Thread.sleep(DELAY_MILLSECONDS);
                stockService.delStockCountCache(sid);
                LOGGER.info("再次删除商品id：[{}] 缓存", sid);
            } catch (Exception e) {
                LOGGER.error("delCacheByThread执行出错", e);
            }
        }
    }


    public Stock getStockbyId(@PathVariable int id) {
        int StockID = id;
        System.out.println(id);
        return stockService.getStockById(StockID);
    }

    /**
     * 查询库存：通过数据库查询库存
     * @param sid
     * @return
     */
    @GetMapping("/getStockByDB/{sid}")
    public String getStockByDB(@PathVariable int sid) {
        int count;
        try {
            count = stockService.getStockById(sid).getCount() - stockService.getStockById(sid).getSale();
        } catch (Exception e) {
            LOGGER.error("查询库存失败：[{}]", e.getMessage());
            return "查询库存失败";
        }
        LOGGER.info("商品Id: [{}] 剩余库存为: [{}]", sid, count);
        return String.format("商品Id: %d 剩余库存为：%d", sid, count);
    }

    /**
     * 查询库存：通过缓存查询库存
     * 缓存命中：返回库存
     * 缓存未命中：查询数据库写入缓存并返回
     * @param sid
     * @return
     */
    @GetMapping("/getStockByCache/{sid}")
    public String getStockByCache(@PathVariable int sid) {
        String count = null;
        try {
            count = stockService.getStockCountByCache(sid);
            if (count == null) {
                count = String.valueOf(stockService.getStockById(sid).getCount() - stockService.getStockById(sid).getSale());
                LOGGER.info("缓存未命中，查询数据库，并写入缓存");
                stockService.setStockCountToCache(sid, count);
            } else {
                LOGGER.info("命中缓存！！！");
            }
        } catch (Exception e) {
            LOGGER.error("查询库存失败：[{}]", e.getMessage());
            return "查询库存失败";
        }
        LOGGER.info("商品Id: [{}] 剩余库存为: [{}]", sid, count);
        return String.format("商品Id: %d 剩余库存为：%s", sid, count);
    }

    @GetMapping("/stock/update/{id}/{sale}")
    public void updateStockById(@PathVariable int id, @PathVariable int sale) {
        Stock stock = new Stock();
        stock.setId(id);
        stock.setSale(sale);
        stockService.updateStockById(stock);
    }

    @GetMapping("/createOptimisticOrder/{sid}")
    public String createOptimisticOrder(@PathVariable int sid) {
        //if (!rateLimiter.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
         //   LOGGER.warn("你被限流了,购买失败...");
         //   return "购买失败";
        //}
        LOGGER.info("等待时间" + rateLimiter.acquire());
        try {
            int number = orderService.createOptimisticOrder(sid);
            LOGGER.info("创建订单成功，剩余库存为: [{}]", number);
            return String.format("创建订单成功，剩余库存为: [{}]", number);
        } catch (Exception e) {
            LOGGER.error("购买失败: [{}]", e.getMessage());
            return "购买失败";
        }
    }

    @GetMapping("/createPessimisticOrder/{sid}")
    public String createPessimisticOrder(@PathVariable int sid) {

        LOGGER.info("等待时间" + rateLimiter.acquire());
        try {
            int number = orderService.createPessimisticOrder(sid);
            LOGGER.info("创建订单成功，剩余库存为: [{}]", number);
            return String.format("创建订单成功，剩余库存为: [{}]", number);
        } catch (Exception e) {
            LOGGER.error("购买失败: [{}]", e.getMessage());
            return "购买失败";
        }
    }


    @GetMapping("/createOrderWithVerifiedUrl")
    public String createOrderWithVerifiedUrl(@RequestParam(value = "sid") Integer sid,
                                             @RequestParam(value = "userId") Integer userId,
                                             @RequestParam(value = "verifyHash") String verifyHash) {
        int stockLeft;
        try {
            stockLeft = orderService.createVerifiedOrder(sid, userId, verifyHash);
            LOGGER.info("购买成功，剩余库存为: [{}]", stockLeft);
        } catch (Exception e) {
            LOGGER.error("购买失败：[{}]", e.getMessage());
            return e.getMessage();
        }
        return String.format("购买成功，剩余库存为：%d", stockLeft);

    }

    @GetMapping("/createOrderWithVerifiedUrlAndLimit")
    public String createOrderWithVerifiedUrlAndLimit(@RequestParam(value = "sid") Integer sid,
                                                     @RequestParam(value = "userId") Integer userId,
                                                     @RequestParam(value = "verifyHash") String verifyHash) {
        int stockLeft;
        try {
            int count = userService.addUserCount(userId);
            LOGGER.info("用户截至该次的访问次数为: [{}]", count);
            boolean isBanned = userService.checkForBanned(userId);
            if (isBanned) {
                return "购买失败，超过频率限制";
            }
            stockLeft = orderService.createVerifiedOrder(sid, userId, verifyHash);
            LOGGER.info("购买成功，剩余库存为: [{}]", stockLeft);
        } catch (Exception e) {
            LOGGER.error("购买失败：[{}]", e.getMessage());
            return e.getMessage();
        }

        return String.format("购买成功，剩余库存为：%d", stockLeft);
    }

    @GetMapping("/createOrderWithCacheV1/{sid}")
    public String createOrderWithCacheV1(@PathVariable int sid) {
        try {
            stockService.delStockCountCache(sid);
            int count = orderService.createPessimisticOrder(sid);
            // 延时指定时间后再次删除缓存
            cachedThreadPool.execute(new delCacheByThread(sid));
            LOGGER.info("购买成功，剩余库存为: [{}]", count);
            return String.format("购买成功，剩余库存为：%d", count);
        } catch (Exception e) {
            LOGGER.error("购买失败：[{}]", e.getMessage());
            return "购买失败，库存不足";
        }
    }


    @GetMapping("/createOrderWithCacheV2/{sid}")
    public String createOrderWithCacheV2(@PathVariable int sid) {
        try {
            int count = orderService.createPessimisticOrder(sid);
            LOGGER.info("购买成功，剩余库存为: [{}]", count);
            stockService.delStockCountCache(sid);
            // 延时指定时间后再次删除缓存
            cachedThreadPool.execute(new delCacheByThread(sid));
            // 假设上述再次删除缓存没成功，通知消息队列进行删除缓存
            sendDelCache(String.valueOf(sid));
            return String.format("购买成功，剩余库存为：%d", count);
        } catch (Exception e) {
            LOGGER.error("购买失败：[{}]", e.getMessage());
            return "购买失败，库存不足";
        }
    }

    @GetMapping("/createOrderWithMq")
    public String createOrderWithMq(@RequestParam(value = "sid") Integer sid,
                                    @RequestParam(value = "userId") Integer userId) {
        try {
            Boolean hasOrder = orderService.checkUserInfoInCache(sid, userId);
            if(hasOrder != null && hasOrder) {
                LOGGER.info("该用户已经抢购过");
                return "你已经抢购过了，不要太贪心.....";
            }
            // 没有下单过，检查缓存中商品是否还有库存
            String count = stockService.getStockCountByCache(sid);
            if(count == null) {
                count = String.valueOf(stockService.getStockById(sid).getCount() - stockService.getStockById(sid).getSale());
                LOGGER.info("缓存未命中，查询数据库，并写入缓存");
                stockService.setStockCountToCache(sid, count);
            }
            if (Integer.parseInt(count) == 0)
                return "秒杀请求失败，库存不足.....";

            // 有库存，则将用户id和商品id封装为消息体传给消息队列处理
            // 注意这里的有库存和已经下单都是缓存中的结论，存在不可靠性，在消息队列中会查表再次验证
            LOGGER.info("有库存：[{}]", count);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("sid", sid);
            jsonObject.put("userId", userId);
            sendToOrderQueue(jsonObject.toJSONString());
            return "秒杀请求提交成功";
        } catch (Exception e) {
            LOGGER.error("下单接口：异步处理订单异常：", e);
            return "秒杀请求失败，服务器正忙.....";
        }
    }

    private void sendDelCache(String message) {
        LOGGER.info("这就去通知消息队列开始重试删除缓存：[{}]", message);
        amqpTemplate.convertAndSend("delCache", message);
    }

    private void sendToOrderQueue(String message) {
        LOGGER.info("这就去通知消息队列开始为用户下单：[{}]", message);
        amqpTemplate.convertAndSend("orderQueue", message);
    }

    /**
     * 检查缓存中用户是否已经生成订单
     * @param sid
     * @return
     */
    @RequestMapping(value = "/checkOrderByUserIdInCache", method = {RequestMethod.GET})
    @ResponseBody
    public String checkOrderByUserIdInCache(@RequestParam(value = "sid") Integer sid,
                                            @RequestParam(value = "userId") Integer userId) {
        // 检查缓存中该用户是否已经下单过
        try {
            Boolean hasOrder = orderService.checkUserInfoInCache(sid, userId);
            if (hasOrder != null && hasOrder) {
                return "恭喜您，已经抢购成功！";
            }
        } catch (Exception e) {
            LOGGER.error("检查订单异常：", e);
        }
        return "很抱歉，你的订单尚未生成，继续排队吧您嘞。";
    }
}
