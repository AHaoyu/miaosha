package miaosha.mapper;


import miaosha.pojo.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {
    User selectByPrimaryKey(Long id);
}
