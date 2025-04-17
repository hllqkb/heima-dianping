package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码格式不正确");
        }
        // 发送验证码
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, 2, TimeUnit.MINUTES);
        log.info("验证码：{}", code);
        return Result.ok("验证码发送成功");
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码格式不正确");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cacheCode == null) {
            return Result.fail("验证码已过期");
        }
        if (!cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        User user=query().eq("phone",phone).one();
        if(user==null){
            // 用户不存在，注册用户
            user=createUserWithPhone(phone);
        }
        // 登录成功，生成token
        String token = UUID.randomUUID().toString().replaceAll("_", "");
        UserDTO userDTO= BeanUtil.copyProperties(user,UserDTO.class);
        Map<String,Object> userMap=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((field, value) ->
                    value.toString()
                ));
        String tokenKey=RedisConstants.LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }


    @Override
    public Result logout() {
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result sign() {
        UserDTO userDto=UserHolder.getUser();
        Long userId=userDto.getId();
        LocalDateTime now=LocalDateTime.now();
        String keySuffix=now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;//用户的一个月的签到BitMap
        int dayOfMonth=now.getDayOfMonth();//获取这个月的第几天
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok("签到成功");
    }
        @Override
        public Result signCount() {
            // 获取当前用户
            UserDTO userDto = UserHolder.getUser();
            Long userId = userDto.getId();
            LocalDateTime now = LocalDateTime.now();
            int dayOfMonth = now.getDayOfMonth(); // 当前日期
            String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM")); // 当月的年月
            String key = USER_SIGN_KEY + userId+":" + keySuffix; // 当月签到的 BITMAP 键

            // 检查 Redis 中是否存在该键
            Boolean hasKey = stringRedisTemplate.hasKey(key);
            if (!hasKey) {
                // 如果没有该键，说明用户本月没有签到记录，返回 0
                return Result.ok(0);
            }

            // 获取 BITMAP 的所有位值,返回一个十进制的数
            List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                    .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

            if (result == null || result.isEmpty()) {
                // 如果获取不到数据，返回 0
                return Result.ok(0);
            }

            Long num= result.get(0); // 获取 BITMAP 的值
            int count = 0; // 连续签到天数
            //从当前天数开始往前，异与到1则继续，到下一位，否则停止
            for (int i = dayOfMonth - 1; i >= 0; i--) {
               if((num&1)==0){
                   break;
               }else{
                   num=num>>1;
                   count++;
               }
            }

            return Result.ok(count); // 返回连续签到天数

    }
    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + phone);
        save(user);
        return user;
    }
}