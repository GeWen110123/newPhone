package com.phone.web.controller.system;

import com.phone.common.core.domain.AjaxResult;
import com.phone.common.core.domain.entity.SysUser;
import com.phone.common.core.domain.model.LoginUser;
import com.phone.common.exception.file.InvalidExtensionException;
import com.phone.framework.web.service.TokenService;
import com.phone.system.service.ISysUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.servlet.http.HttpServletRequest;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SysProfileSecurityTest {
    private final ISysUserService users = mock(ISysUserService.class);
    private final TokenService tokens = mock(TokenService.class);
    private final SysProfileController controller = new SysProfileController();
    private SysUser current;

    @BeforeEach void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        current = new SysUser(); current.setUserId(42L); current.setUserName("profile-test");
        when(tokens.getLoginUser(any(HttpServletRequest.class))).thenReturn(new LoginUser(current, new HashSet<>()));
    }
    @AfterEach void tearDown() { RequestContextHolder.resetRequestAttributes(); }

    @Test void profileOnlyUpdatesAllowedFieldsForCurrentIdentity() {
        SysUser input = new SysUser();
        input.setUserId(1L); input.setUserName("changed-name"); input.setDeptId(99L);
        input.setPassword("must-not-persist"); input.setStatus("1"); input.setAvatar("untrusted.html");
        input.setLoginIp("untrusted"); input.setRemark("untrusted");
        input.setNickName("Updated"); input.setSex("1"); input.setEmail("member@example.test");
        input.setPhonenumber("13800000000");
        when(users.updateUserProfile(any())).thenReturn(1);
        AjaxResult result = controller.updateProfile(input);
        assertEquals(200, result.get("code"));
        ArgumentCaptor<SysUser> captured = ArgumentCaptor.forClass(SysUser.class);
        verify(users).updateUserProfile(captured.capture());
        SysUser safe = captured.getValue();
        assertEquals(42L, safe.getUserId()); assertEquals("Updated", safe.getNickName());
        assertEquals("1", safe.getSex()); assertEquals(input.getEmail(), safe.getEmail());
        assertEquals(input.getPhonenumber(), safe.getPhonenumber());
        assertNull(safe.getUserName()); assertNull(safe.getDeptId()); assertNull(safe.getPassword());
        assertNull(safe.getStatus()); assertNull(safe.getAvatar()); assertNull(safe.getLoginIp()); assertNull(safe.getRemark());
        verify(users).checkPhoneUnique(safe); verify(users).checkEmailUnique(safe);
        assertEquals("profile-test", current.getUserName()); assertEquals("Updated", current.getNickName());
    }

    @Test void duplicateContactDoesNotPersistOrRefreshToken() {
        SysUser input = new SysUser(); input.setPhonenumber("13800000000");
        when(users.checkPhoneUnique(any())).thenReturn("1");
        assertEquals(500, controller.updateProfile(input).get("code"));
        verify(users, never()).updateUserProfile(any()); verify(tokens, never()).setLoginUser(any());
    }

    @Test void avatarRejectsHtmlBeforeWritingFileOrUpdatingUser() {
        MockMultipartFile file = new MockMultipartFile("avatarfile", "avatar.html", "text/html", "<html>audit</html>".getBytes());
        assertThrows(InvalidExtensionException.class, () -> controller.avatar(file));
        verify(users, never()).updateUserAvatar(anyString(), anyString());
    }
}
