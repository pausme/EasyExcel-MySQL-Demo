package com.huang.demo.security.service;

import com.huang.demo.security.api.dto.AuthTokenResponse;
import com.huang.demo.security.api.dto.LoginRequest;

public interface AuthService {

    AuthTokenResponse login(LoginRequest request);

    AuthTokenResponse refresh(String refreshToken);
}
