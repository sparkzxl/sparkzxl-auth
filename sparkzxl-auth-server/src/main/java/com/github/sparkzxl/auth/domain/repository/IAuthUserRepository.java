package com.github.sparkzxl.auth.domain.repository;

import com.github.sparkzxl.auth.domain.model.aggregates.AuthUserBasicInfo;
import com.github.sparkzxl.auth.domain.model.aggregates.UserCount;
import com.github.sparkzxl.auth.infrastructure.entity.AuthUser;

import java.util.List;

/**
 * description: 用户仓储层
 *
 * @author charles.zhou
 * @date 2020-06-05 20:39:15
 */
public interface IAuthUserRepository {


    /**
     * 根据用户id查询用户信息
     *
     * @param id
     * @return
     */
    AuthUser selectById(Long id);

    /**
     * 根据账户查询用户信息
     *
     * @param account
     * @return
     */
    AuthUser selectByAccount(String account);

    /**
     * 获取用户权限集
     *
     * @param id id
     * @return Set<String>
     */
    List<String> getAuthUserPermissions(Long id);

    /**
     * 获取用户角色
     *
     * @param id 用户id
     * @return
     */
    List<String> getAuthUserRoles(Long id);

    /**
     * 删除用户关联信息
     *
     * @param ids 用户ids
     */
    void deleteUserRelation(List<Long> ids);


    /**
     * 分页查询用户列表
     *
     * @param authUser 查询对象
     * @return PageInfo<AuthUser>
     */
    List<AuthUser> getAuthUserList(AuthUser authUser);

    /**
     * 根据用户id获取用户全量信息
     *
     * @param userId 用户id
     * @return AuthUserBasicInfo
     */
    AuthUserBasicInfo getAuthUserBasicInfo(Long userId);

    /**
     * 保存用户信息
     *
     * @param authUser 用户信息
     */
    void saveAuthUserInfo(AuthUser authUser);

    /**
     * 根据领域池code删除用户
     *
     * @param realmCode 领域池code
     */
    void deleteRealmPoolUser(String realmCode);

    /**
     * 删除用户信息
     *
     * @param ids 用户ids
     * @return boolean
     */
    boolean deleteAuthUser(List<Long> ids);

    /**
     * 获取用户数量
     *
     * @param realmCodeList 领域code
     * @return List<UserCount>
     */
    List<UserCount> userCount(List<String> realmCodeList);
}
