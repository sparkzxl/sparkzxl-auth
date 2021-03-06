package com.github.sparkzxl.auth.domain.service;

import cn.hutool.core.bean.OptionalBean;
import cn.hutool.extra.pinyin.PinyinUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.javafaker.Faker;
import com.github.pagehelper.PageInfo;
import com.github.sparkzxl.auth.application.event.ImportUserDataListener;
import com.github.sparkzxl.auth.application.service.*;
import com.github.sparkzxl.auth.application.service.es.IEsUserAttributeService;
import com.github.sparkzxl.auth.domain.model.aggregates.AuthUserBasicInfo;
import com.github.sparkzxl.auth.domain.model.aggregates.MenuBasicInfo;
import com.github.sparkzxl.auth.domain.model.aggregates.excel.UserExcel;
import com.github.sparkzxl.auth.domain.model.vo.AuthUserBasicVO;
import com.github.sparkzxl.auth.domain.repository.IAuthUserRepository;
import com.github.sparkzxl.auth.infrastructure.constant.BizConstant;
import com.github.sparkzxl.auth.infrastructure.convert.AuthUserConvert;
import com.github.sparkzxl.auth.infrastructure.entity.AuthUser;
import com.github.sparkzxl.auth.infrastructure.entity.CoreOrg;
import com.github.sparkzxl.auth.infrastructure.entity.CoreStation;
import com.github.sparkzxl.auth.infrastructure.enums.SexEnum;
import com.github.sparkzxl.auth.infrastructure.mapper.AuthUserMapper;
import com.github.sparkzxl.auth.interfaces.dto.user.UserQueryDTO;
import com.github.sparkzxl.auth.interfaces.dto.user.UserSaveDTO;
import com.github.sparkzxl.auth.interfaces.dto.user.UserUpdateDTO;
import com.github.sparkzxl.constant.EntityConstant;
import com.github.sparkzxl.database.base.service.impl.SuperCacheServiceImpl;
import com.github.sparkzxl.database.dto.PageParams;
import com.github.sparkzxl.database.utils.PageInfoUtils;
import com.github.sparkzxl.entity.core.AuthUserInfo;
import com.github.sparkzxl.entity.data.RemoteData;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * description: ???????????? ???????????????
 *
 * @author charles.zhou
 * @date 2020-05-24 12:22:57
 */
@Service
@Slf4j
public class UserServiceImpl extends SuperCacheServiceImpl<AuthUserMapper, AuthUser> implements IUserService {

    @Autowired
    private IAuthUserRepository authUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private IMenuService authMenuService;
    @Autowired
    private ICoreStationService coreStationService;
    @Autowired
    private ICoreOrgService coreOrgService;
    @Autowired
    private IDictionaryItemService dictionaryItemService;
    @Autowired
    private IEsUserAttributeService esUserAttributeService;

    @Override
    public AuthUserInfo<Long> getAuthUserInfo(String username) {
        AuthUser authUser = authUserRepository.selectByAccount(username);
        if (ObjectUtils.isNotEmpty(authUser)) {
            AuthUserInfo<Long> authUserInfo = AuthUserConvert.INSTANCE.convertAuthUserInfo(authUser);
            List<String> authUserRoles = getAuthUserRoles(authUser.getId());
            authUserRoles.add(BizConstant.USER_CODE);
            authUserInfo.setAuthorityList(authUserRoles);
            Map<String, Object> extraInfo = Maps.newHashMap();
            extraInfo.put("org", OptionalBean.ofNullable(authUser.getOrg()).getBean(RemoteData::getData).get());
            extraInfo.put("station", OptionalBean.ofNullable(authUser.getStation()).getBean(RemoteData::getData).get());
            extraInfo.put("mobile", authUser.getMobile());
            extraInfo.put("email", authUser.getEmail());
            extraInfo.put("education", OptionalBean.ofNullable(authUser.getEducation()).getBean(RemoteData::getData).get());
            extraInfo.put("positionStatus", OptionalBean.ofNullable(authUser.getPositionStatus()).getBean(RemoteData::getData).get());
            authUserInfo.setExtraInfo(extraInfo);
            return authUserInfo;
        }
        return null;
    }

    @Override
    public AuthUser getByAccount(String username) {
        return authUserRepository.selectByAccount(username);
    }

    @Override
    public PageInfo<AuthUser> getAuthUserPage(PageParams<UserQueryDTO> params) {
        AuthUser authUser = AuthUserConvert.INSTANCE.convertAuthUser(params.getModel());
        Map<String, Object> userAttribute = authUser.getAttribute();
        List<Long> userIdList = Lists.newArrayList();
        List<Map> searchDocList = Lists.newArrayList();
        if (MapUtils.isNotEmpty(userAttribute)) {
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            for (String key : userAttribute.keySet()) {
                searchSourceBuilder.query(QueryBuilders.termQuery(key, userAttribute.get(key)));
            }
            searchDocList = esUserAttributeService.searchDocList(BizConstant.INDEX_USER_ATTRIBUTE, searchSourceBuilder, Map.class);
            userIdList = searchDocList.stream().filter(x -> ObjectUtils.isNotEmpty(x.get(EntityConstant.COLUMN_ID))).map(x -> Long.valueOf((String) x.get(EntityConstant.COLUMN_ID))).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(userIdList)) {
                return new PageInfo<>();
            }
        }
        params.startPage();
        List<AuthUser> authUserList = authUserRepository.getAuthUserList(authUser, userIdList);
        PageInfo<AuthUser> authUserPageInfo = PageInfoUtils.pageInfo(authUserList);
        List<AuthUser> userList = authUserPageInfo.getList();
        if (CollectionUtils.isNotEmpty(userList)) {
            buildQueryUserAttribute(searchDocList, userList);
            authUserPageInfo.setList(userList);
        }
        return authUserPageInfo;
    }

    @Override
    public boolean saveAuthUser(UserSaveDTO userSaveDTO) {
        AuthUser authUser = AuthUserConvert.INSTANCE.convertAuthUser(userSaveDTO);
        boolean result = authUserRepository.saveAuthUser(authUser);
        Long userId = authUser.getId();
        Map<String, Object> userAttributeMap = authUser.getAttribute();
        if (MapUtils.isNotEmpty(userAttributeMap)) {
            userAttributeMap.put(EntityConstant.COLUMN_ID, String.valueOf(userId));
            esUserAttributeService.saveDoc(BizConstant.INDEX_USER_ATTRIBUTE, String.valueOf(userId), userAttributeMap);
        }
        return result;
    }

    @Override
    public boolean updateAuthUser(UserUpdateDTO userUpdateDTO) {
        AuthUser authUser = AuthUserConvert.INSTANCE.convertAuthUser(userUpdateDTO);
        boolean result = authUserRepository.updateAuthUser(authUser);
        Map<String, Object> userAttributeMap = authUser.getAttribute();
        Long userId = authUser.getId();
        esUserAttributeService.deleteDocById(BizConstant.INDEX_USER_ATTRIBUTE, String.valueOf(userId));
        if (MapUtils.isNotEmpty(userAttributeMap)) {
            userAttributeMap.put(EntityConstant.COLUMN_ID, String.valueOf(userId));
            esUserAttributeService.saveDoc(BizConstant.INDEX_USER_ATTRIBUTE, String.valueOf(userId), userAttributeMap);
        }
        return result;
    }

    @Override
    public void deleteOrgIds(List<Long> orgIds) {
        LambdaUpdateWrapper<AuthUser> userUpdateWrapper = new LambdaUpdateWrapper<>();
        userUpdateWrapper.set(AuthUser::getOrg, null);
        userUpdateWrapper.in(AuthUser::getOrg, orgIds);
        update(userUpdateWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean mockUserData() {
        Faker faker = new Faker(Locale.CHINA);
        for (int i = 0; i < 10; i++) {
            String password = passwordEncoder.encode("123456");
            AuthUser userInfo = new AuthUser();
            userInfo.setPassword(password);
            String name = faker.name().fullName();
            userInfo.setName(name);
            userInfo.setMobile(faker.phoneNumber().cellPhone());
            RemoteData<Long, CoreOrg> orgRemoteData = new RemoteData<>();
            orgRemoteData.setKey(112L);
            userInfo.setOrg(orgRemoteData);
            RemoteData<Long, CoreStation> stationRemoteData = new RemoteData<>();
            stationRemoteData.setKey(106L);
            userInfo.setStation(stationRemoteData);
            String pinyin = StringUtils.deleteWhitespace(PinyinUtil.getPinyin(name));
            userInfo.setAccount(pinyin);
            String email = pinyin.concat("@163.com");
            userInfo.setEmail(email);
            if (i % 2 == 0) {
                userInfo.setSex(SexEnum.MAN);
            } else {
                userInfo.setSex(SexEnum.WOMAN);
            }
            userInfo.setStatus(true);
            RemoteData<String, String> nationRemoteData = new RemoteData<>();
            nationRemoteData.setKey("mz_hanz");
            userInfo.setNation(nationRemoteData);

            RemoteData<String, String> educationRemoteData = new RemoteData<>();
            educationRemoteData.setKey("BOSHIHOU");
            userInfo.setEducation(educationRemoteData);

            RemoteData<String, String> positionRemoteData = new RemoteData<>();
            positionRemoteData.setKey("WORKING");
            userInfo.setPositionStatus(positionRemoteData);
            log.info(" ???????????????{}", JSONUtil.toJsonPrettyStr(userInfo));
            saveUserInfo(userInfo);
        }
        return true;
    }

    private void saveUserInfo(AuthUser userInfo) {
        save(userInfo);
    }

    @Override
    public AuthUserBasicVO getAuthUserBasicInfo(AuthUserInfo<Long> authUserInfo) {
        AuthUserBasicInfo authUserBasicInfo = authUserRepository.getAuthUserBasicInfo(authUserInfo.getId());
        if (ObjectUtils.isNotEmpty(authUserBasicInfo) && ObjectUtils.isNotEmpty(authUserBasicInfo.getId())) {
            Map userAttribute = esUserAttributeService.searchDocById(BizConstant.INDEX_USER_ATTRIBUTE, String.valueOf(authUserBasicInfo.getId()), Map.class);
            authUserBasicInfo.setAttribute(userAttribute);
        }
        return AuthUserConvert.INSTANCE.convertAuthUserBasicVO(authUserBasicInfo);
    }

    @Override
    public List<MenuBasicInfo> routers(Long userId) {
        return authMenuService.routers(userId);
    }

    @Override
    public Integer importUserData(MultipartFile multipartFile) {
        ImportUserDataListener importUserDataListener = new ImportUserDataListener();
        importUserDataListener.setAuthUserService(this);
        importUserDataListener.setCoreOrgService(coreOrgService);
        importUserDataListener.setCoreStationService(coreStationService);
        importUserDataListener.setDictionaryItemService(dictionaryItemService);
        importUserDataListener.setPasswordEncoder(passwordEncoder);
        try {
            EasyExcel.read(multipartFile.getInputStream(), UserExcel.class, importUserDataListener)
                    .sheet(0).doRead();
            return importUserDataListener.getCount();
        } catch (IOException e) {
            e.printStackTrace();
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.error("??????Excel???????????????{}", e.getMessage());
        }
        return 0;
    }

    @Override
    public List<AuthUser> userList(UserQueryDTO userQueryDTO) {
        AuthUser authUser = AuthUserConvert.INSTANCE.convertAuthUser(userQueryDTO);
        Map<String, Object> userAttribute = authUser.getAttribute();
        List<Long> userIdList = Lists.newArrayList();
        List<Map> searchDocList = Lists.newArrayList();
        if (MapUtils.isNotEmpty(userAttribute)) {
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            for (String key : userAttribute.keySet()) {
                searchSourceBuilder.query(QueryBuilders.termQuery(key, userAttribute.get(key)));
            }
            searchDocList = esUserAttributeService.searchDocList(BizConstant.INDEX_USER_ATTRIBUTE, searchSourceBuilder, Map.class);
            userIdList = searchDocList.stream().filter(x -> ObjectUtils.isNotEmpty(x.get(EntityConstant.COLUMN_ID))).map(x -> Long.valueOf((String) x.get(EntityConstant.COLUMN_ID))).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(userIdList)) {
                return Lists.newArrayList();
            }
        }
        List<AuthUser> userList = authUserRepository.getAuthUserList(authUser, userIdList);
        if (CollectionUtils.isNotEmpty(userList)) {
            buildQueryUserAttribute(searchDocList, userList);
        }
        return userList;
    }

    private void buildQueryUserAttribute(List<Map> searchDocList, List<AuthUser> userList) {
        Map<String, Map> searchUserAttribute = Maps.newHashMap();
        if (CollectionUtils.isNotEmpty(searchDocList)) {
            for (Map doc : searchDocList) {
                String id = doc.get(EntityConstant.COLUMN_ID).toString();
                searchUserAttribute.put(id, doc);
            }
        } else {
            List<String> userIdStrList = userList.stream().map(x -> String.valueOf(x.getId())).collect(Collectors.toList());
            searchUserAttribute = esUserAttributeService.searchDocsMapByIdList(BizConstant.INDEX_USER_ATTRIBUTE, userIdStrList, Map.class);
        }
        System.out.println(JSONUtil.toJsonPrettyStr(searchUserAttribute));
        Map<String, Map> finalSearchUserAttribute = searchUserAttribute;
        userList.forEach(user -> {
            Map map = finalSearchUserAttribute.get(String.valueOf(user.getId()));
            if (MapUtils.isNotEmpty(map)) {
                map.remove(EntityConstant.COLUMN_ID);
                user.setAttribute(map);
            }
        });
    }

    @Override
    public boolean deleteAuthUser(List<Long> ids) {
        boolean result = authUserRepository.deleteAuthUser(ids);
        if (CollectionUtils.isNotEmpty(ids)) {
            esUserAttributeService.deleteDocByIds(BizConstant.INDEX_USER_ATTRIBUTE, ids.stream().map(String::valueOf).collect(Collectors.toList()));
        }
        return result;
    }

    @Override
    public List<String> getAuthUserRoles(Long id) {
        return authUserRepository.getAuthUserRoles(id);
    }

    @Override
    protected String getRegion() {
        return BizConstant.USER;
    }

}
