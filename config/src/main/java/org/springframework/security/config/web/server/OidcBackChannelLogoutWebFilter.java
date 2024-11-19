/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.config.web.server;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler;
import org.springframework.util.Assert;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * A filter for the Client-side OIDC Back-Channel Logout endpoint
 *
 * @author Josh Cummings
 * @since 6.2
 * @see <a target="_blank" href=
 * "https://openid.net/specs/openid-connect-backchannel-1_0.html">OIDC Back-Channel Logout
 * Spec</a>
 */
class OidcBackChannelLogoutWebFilter implements WebFilter {

	private final Log logger = LogFactory.getLog(getClass());

	private final ServerAuthenticationConverter authenticationConverter;

	private final ReactiveAuthenticationManager authenticationManager;

	private ServerLogoutHandler logoutHandler = new OidcBackChannelServerLogoutHandler();

	/**
	 * Construct an {@link OidcBackChannelLogoutWebFilter}
	 * @param authenticationConverter the {@link AuthenticationConverter} for deriving
	 * Logout Token authentication
	 * @param authenticationManager the {@link AuthenticationManager} for authenticating
	 * Logout Tokens
	 */
	OidcBackChannelLogoutWebFilter(ServerAuthenticationConverter authenticationConverter,
			ReactiveAuthenticationManager authenticationManager) {
		Assert.notNull(authenticationConverter, "authenticationConverter cannot be null");
		Assert.notNull(authenticationManager, "authenticationManager cannot be null");
		this.authenticationConverter = authenticationConverter;
		this.authenticationManager = authenticationManager;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		return this.authenticationConverter.convert(exchange).onErrorResume(AuthenticationException.class, (ex) -> {
			this.logger.debug("Failed to process OIDC Back-Channel Logout", ex);
			if (ex instanceof AuthenticationServiceException) {
				return Mono.error(ex);
			}
			return handleAuthenticationFailure(exchange.getResponse(), ex).then(Mono.empty());
		})
			.switchIfEmpty(chain.filter(exchange).then(Mono.empty()))
			.flatMap(this.authenticationManager::authenticate)
			.onErrorResume(AuthenticationException.class, (ex) -> {
				this.logger.debug("Failed to process OIDC Back-Channel Logout", ex);
				if (ex instanceof AuthenticationServiceException) {
					return Mono.error(ex);
				}
				return handleAuthenticationFailure(exchange.getResponse(), ex).then(Mono.empty());
			})
			.flatMap((authentication) -> {
				WebFilterExchange webFilterExchange = new WebFilterExchange(exchange, chain);
				return this.logoutHandler.logout(webFilterExchange, authentication);
			});
	}

	private Mono<Void> handleAuthenticationFailure(ServerHttpResponse response, Exception ex) {
		this.logger.debug("Failed to process OIDC Back-Channel Logout", ex);
		response.setRawStatusCode(HttpServletResponse.SC_BAD_REQUEST);
		OAuth2Error error = oauth2Error(ex);
		byte[] bytes = String.format("""
				{
					"error_code": "%s",
					"error_description": "%s",
					"error_uri: "%s"
				}
				""", error.getErrorCode(), error.getDescription(), error.getUri()).getBytes(StandardCharsets.UTF_8);
		DataBuffer buffer = response.bufferFactory().wrap(bytes);
		return response.writeWith(Flux.just(buffer));
	}

	private OAuth2Error oauth2Error(Exception ex) {
		if (ex instanceof OAuth2AuthenticationException oauth2) {
			return oauth2.getError();
		}
		return new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST, ex.getMessage(),
				"https://openid.net/specs/openid-connect-backchannel-1_0.html#Validation");
	}

	/**
	 * The strategy for expiring all Client sessions indicated by the logout request.
	 * Defaults to {@link OidcBackChannelServerLogoutHandler}.
	 * @param logoutHandler the {@link LogoutHandler} to use
	 */
	void setLogoutHandler(ServerLogoutHandler logoutHandler) {
		Assert.notNull(logoutHandler, "logoutHandler cannot be null");
		this.logoutHandler = logoutHandler;
	}

}
