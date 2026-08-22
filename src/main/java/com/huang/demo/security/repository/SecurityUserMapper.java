package com.huang.demo.security.repository;

import com.huang.demo.security.domain.SecurityUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface SecurityUserMapper {

    void createTableIfAbsent();

    Optional<SecurityUser> findByUsername(@Param("username") String username);

    Optional<SecurityUser> findByUserId(@Param("userId") String userId);

    int insert(SecurityUser user);

    int countByUsername(@Param("username") String username);
}
