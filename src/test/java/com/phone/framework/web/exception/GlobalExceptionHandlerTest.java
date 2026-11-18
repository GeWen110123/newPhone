package com.phone.framework.web.exception;

import org.junit.jupiter.api.Test;
import com.phone.common.core.domain.AjaxResult;
import com.phone.common.exception.CustomException;
import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    @Test void unexpectedFailuresDoNotExposeSqlOrFilePaths() {
        AjaxResult response = handler.handleException(new Exception("SQLSyntaxError at mapper/private.xml: SELECT secret FROM users"));
        assertEquals(500, response.get("code"));
        assertEquals("服务暂时不可用，请稍后重试或联系管理员", response.get("msg"));
    }
    @Test void explicitBusinessErrorsKeepTheirActionableMessage() {
        assertEquals("旧密码错误", handler.businessException(new CustomException("旧密码错误")).get("msg"));
    }
}
