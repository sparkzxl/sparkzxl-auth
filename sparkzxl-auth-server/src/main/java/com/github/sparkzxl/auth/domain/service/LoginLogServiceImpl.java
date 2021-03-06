package com.github.sparkzxl.auth.domain.service;

import com.github.pagehelper.PageInfo;
import com.github.sparkzxl.auth.application.service.ILoginLogService;
import com.github.sparkzxl.auth.domain.model.aggregates.LoginStatus;
import com.github.sparkzxl.auth.domain.repository.IAuthUserRepository;
import com.github.sparkzxl.auth.domain.repository.ILoginLogRepository;
import com.github.sparkzxl.auth.infrastructure.constant.BizConstant;
import com.github.sparkzxl.auth.infrastructure.entity.AuthUser;
import com.github.sparkzxl.auth.infrastructure.entity.LoginLog;
import com.github.sparkzxl.auth.infrastructure.entity.LoginLogCount;
import com.github.sparkzxl.auth.infrastructure.mapper.LoginLogMapper;
import com.github.sparkzxl.auth.interfaces.dto.log.LoginLogQueryDTO;
import com.github.sparkzxl.core.utils.BuildKeyUtils;
import com.github.sparkzxl.database.base.service.impl.SuperCacheServiceImpl;
import com.github.sparkzxl.database.dto.PageParams;
import com.github.sparkzxl.entity.core.AuthUserInfo;
import com.github.sparkzxl.entity.core.UserAgentEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * description：系统日志 服务实现类
 *
 * @author charles.zhou
 * @date 2020/6/17 0017
 */
@Service
public class LoginLogServiceImpl extends SuperCacheServiceImpl<LoginLogMapper, LoginLog> implements ILoginLogService {

    private IAuthUserRepository authUserRepository;
    private ILoginLogRepository loginLogRepository;

    @Autowired
    public void setAuthUserRepository(IAuthUserRepository authUserRepository) {
        this.authUserRepository = authUserRepository;
    }

    @Autowired
    public void setLoginLogRepository(ILoginLogRepository loginLogRepository) {
        this.loginLogRepository = loginLogRepository;
    }

    @Override
    public void save(LoginStatus<Long> loginStatus) {
        AuthUser authUser;
        if (loginStatus.getId() != null) {
            authUser = authUserRepository.selectById(loginStatus.getId());
        } else {
            authUser = authUserRepository.selectByAccount(loginStatus.getAccount());
        }
        UserAgentEntity userAgentEntity = loginStatus.getUserAgentEntity();
        System.out.println(userAgentEntity);
        LoginLog loginLog = LoginLog.builder()
                .requestIp(userAgentEntity.getRequestIp())
                .userId(loginStatus.getId())
                .userName(loginStatus.getName())
                .account(loginStatus.getAccount())
                .description(loginStatus.getDescription())
                .loginDate(LocalDateTime.now())
                .ua(userAgentEntity.getUa())
                .browser(userAgentEntity.getBrowser())
                .browserVersion(userAgentEntity.getBrowserVersion())
                .operatingSystem(userAgentEntity.getOperatingSystem())
                .location(userAgentEntity.getLocation())
                .build();
        if (authUser != null) {
            loginLog.setAccount(authUser.getAccount()).setUserId(authUser.getId()).setUserName(authUser.getName())
                    .setCreateUser(authUser.getId());
        }
        loginLogRepository.saveLoginLog(loginLog);
        LocalDate now = LocalDate.now();
        LocalDate tenDays = now.plusDays(-9);
        generalCacheService.remove(BizConstant.LOGIN_LOG_TOTAL);
        generalCacheService.remove(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_TODAY, now));
        generalCacheService.remove(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_TODAY_IP, now));
        generalCacheService.remove(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_BROWSER));
        generalCacheService.remove(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_SYSTEM));
        if (authUser != null) {
            generalCacheService.remove(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_TEN_DAY, tenDays, loginStatus.getAccount()));
        }
    }

    @Override
    public Long findTotalVisitCount() {
        return generalCacheService.get(BizConstant.LOGIN_LOG_TOTAL);
    }

    @Override
    public Long findTodayVisitCount() {
        LocalDate now = LocalDate.now();
        return generalCacheService.get(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_TODAY, now));
    }

    @Override
    public Long findTodayIp() {
        LocalDate now = LocalDate.now();
        return generalCacheService.get(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_TODAY_IP, now));
    }

    @Override
    public List<LoginLogCount> findLastTenDaysVisitCount(String account) {
        LocalDate tenDays = LocalDate.now().plusDays(-9);
        return generalCacheService.get(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_TEN_DAY, tenDays, account),
                (key) -> loginLogRepository.findLastTenDaysVisitCount(tenDays, account));
    }

    @Override
    public List<LoginLogCount> findByBrowser() {
        return generalCacheService.get(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_BROWSER),
                (key) -> loginLogRepository.findByBrowser());
    }

    @Override
    public List<LoginLogCount> findByOperatingSystem() {
        return generalCacheService.get(BuildKeyUtils.generateKey(BizConstant.LOGIN_LOG_SYSTEM),
                (key) -> loginLogRepository.findByOperatingSystem());
    }

    @Override
    public boolean clearLog(LocalDateTime clearBeforeTime, Integer clearBeforeNum) {
        return loginLogRepository.clearLog(clearBeforeTime, clearBeforeNum);
    }

    @Override
    public PageInfo<LoginLog> getLoginLogPage(AuthUserInfo<Long> authUserInfo, PageParams<LoginLogQueryDTO> pageParams) {
        return loginLogRepository.getLoginLogPage(pageParams.getPageNum(), pageParams.getPageSize(), authUserInfo.getId(), pageParams.getModel().getAccount(), pageParams.getModel().getStartTime(),
                pageParams.getModel().getEndTime());
    }

    @Override
    public boolean deleteLoginLog(List<Long> ids) {
        return loginLogRepository.deleteLoginLog(ids);
    }

    @Override
    protected String getRegion() {
        return "login_log";
    }
}
