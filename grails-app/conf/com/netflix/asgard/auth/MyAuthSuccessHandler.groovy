package com.netflix.asgard.auth

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler

import com.netflix.asgard.ConfigService

public class MyAuthSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
	private ConfigService configService
	@Override
	protected String determineTargetUrl(HttpServletRequest request,HttpServletResponse response) {
		if (configService.userConfigured){
			return '/'
		}else{
			return super.determineTargetUrl(request, response);
		}
	}
	public void setConfigService(ConfigService configService){
		this.configService = configService
	}	
	
	public ConfigService getConfigService(){
		return this.configService
	}
}