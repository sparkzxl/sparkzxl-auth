package com.github.sparkzxl.auth.infrastructure.security.logout;

import com.github.sparkzxl.auth.application.service.IRealmManagerService;
import com.github.sparkzxl.auth.application.service.IUserService;
import com.github.sparkzxl.core.base.ResponseResultUtils;
import com.github.sparkzxl.core.context.BaseContextConstants;
import com.github.sparkzxl.core.entity.AuthUserInfo;
import com.github.sparkzxl.core.utils.BuildKeyUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * description: 退出登录成功处理
 *
 * @author charles.zhou
 */
@Component
@Slf4j
public class LogoutSuccessHandlerImpl implements LogoutSuccessHandler {

    private TokenStore tokenStore;
    private IUserService userService;
    private RedisTemplate<String, Object> redisTemplate;
    private IRealmManagerService realmManagerService;

    @Autowired
    public void setTokenStore(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Autowired
    public void setUserService(IUserService userService) {
        this.userService = userService;
    }

    @Autowired
    public void setRedisTemplate(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Autowired
    public void setRealmManagerService(IRealmManagerService realmManagerService) {
        this.realmManagerService = realmManagerService;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Authentication authentication) {
        String token = httpServletRequest.getHeader("token");
        log.info("退出登录成功：{}", token);
        token = StringUtils.removeStartIgnoreCase(token, BaseContextConstants.BEARER_TOKEN);
        if (token != null) {
            OAuth2AccessToken accessToken = tokenStore.readAccessToken(token);
            if (accessToken != null) {
                tokenStore.removeAccessToken(accessToken);
                tokenStore.removeRefreshToken(accessToken.getRefreshToken());
                Map<String, Object> additionalInformation = accessToken.getAdditionalInformation();
                String username = (String) additionalInformation.get("username");
                boolean realmStatus = (boolean) additionalInformation.get("realmStatus");
                AuthUserInfo<Long> authUserInfo;
                if (realmStatus) {
                    authUserInfo = realmManagerService.getAuthUserInfo(username);
                } else {
                    authUserInfo = userService.getAuthUserInfo(username);
                }
                String authUserInfoKey = BuildKeyUtils.generateKey(BaseContextConstants.AUTH_USER_TOKEN, authUserInfo.getId());
                redisTemplate.opsForHash().delete(authUserInfoKey, accessToken.getValue());
            }
        }
        ResponseResultUtils.writeResponseOutMsg(httpServletResponse, 200, "退出登录成功", true);
    }
}
