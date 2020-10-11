package miaosha.service;


import miaosha.Utils.CacheKey;
import miaosha.mapper.StockMapper;
import miaosha.mapper.UserMapper;
import miaosha.pojo.Stock;
import miaosha.pojo.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@Service
public class UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);
    private static final String SALT = "randomString1111";
    private static final int ALLOW_COUNT = 10;
    @Autowired
    UserMapper userMapper;

    @Autowired
    StockMapper stockMapper;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    public String getVerifyHash(Integer stockId, Integer userId) throws Exception {
        LOGGER.info("请自行验证是否在抢购时间内");

        User user = userMapper.selectByPrimaryKey(userId.longValue());
        if (user == null) {
            throw new Exception("用户不存在");
        }
        LOGGER.info("用户信息：[{}]", user.toString());

        // 检查商品合法性
        Stock stock = stockMapper.getStockById(stockId);
        if (stock == null) {
            throw new Exception("商品不存在");
        }
        LOGGER.info("商品信息：[{}]", stock.toString());

        String verify = SALT + stockId + userId;
        String verifyHash = DigestUtils.md5DigestAsHex(verify.getBytes());

        // 将hash和用户商品信息存入redis
        String hashKey = CacheKey.HASH_KEY.getKey() + "_" + stockId + "_" + userId;
        stringRedisTemplate.opsForValue().set(hashKey, verifyHash, 3600, TimeUnit.SECONDS);
        LOGGER.info("Redis写入：[{}] [{}]", hashKey, verifyHash);

        return verifyHash;
    }

    public int addUserCount(Integer userId) {
        String limitKey = CacheKey.LIMIT_KEY.getKey() + "_" + userId;
        String limitNum = stringRedisTemplate.opsForValue().get(limitKey);
        int limit = 1;
        if (limitNum == null) {
            stringRedisTemplate.opsForValue().set(limitKey, "1", 3600, TimeUnit.SECONDS);
        } else {
            limit = Integer.parseInt(limitNum) + 1;
            stringRedisTemplate.opsForValue().set(limitKey, String.valueOf(limit), 3600, TimeUnit.SECONDS);
        }
        return limit;
    }

    public boolean checkForBanned(Integer userId) {
        String limitKey = CacheKey.LIMIT_KEY.getKey() + "_" + userId;
        String limitNum = stringRedisTemplate.opsForValue().get(limitKey);
        if (limitNum == null) {
            LOGGER.error("该用户没有访问申请验证值记录，疑似异常");
            return true;
        }
        return Integer.parseInt(limitNum) > ALLOW_COUNT;
    }
}
