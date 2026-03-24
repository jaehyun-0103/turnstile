package com.turnstile.backend.domain.auth.resolver;

import com.turnstile.backend.domain.auth.annotation.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 컨트롤러 메서드 파라미터에 현재 로그인한 사용자 식별자를 주입하는 리졸버입니다.
 */
@Component
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        boolean hasLoginUserAnnotation = parameter.hasParameterAnnotation(LoginUser.class);
        boolean isLongType = Long.class.isAssignableFrom(parameter.getParameterType());
        return hasLoginUserAnnotation && isLongType;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();

        return request.getAttribute("userId"); // Interceptor에서 request에 저장한 userId 반환
    }
}