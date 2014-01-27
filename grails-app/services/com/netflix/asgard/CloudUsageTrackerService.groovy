package com.netflix.asgard

import com.netflix.asgard.model.MonitorBucketType;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime
import org.springframework.security.core.context.SecurityContextHolder

class CloudUsageTrackerService {
	def configService

	boolean addAuditData(UserContext userContext, AuditApplicationType applicationType,Action action,Status status,List id) {

		String date = new DateTime().toString()
		String user = SecurityContextHolder?.getContext()?.getAuthentication()?.getName()?:'default'
		String message = user+' has '+action+'D '+applicationType + " ID: "+id
	
		String region = userContext.region.code
		String cloudProvider = configService.provider
		String userAccount = configService.getProviderValues(cloudProvider).userName
		String auditId = RandomStringUtils.randomAlphanumeric(4)
		CloudUsageTracker cloudUsageTracker = new CloudUsageTracker(message:message.toUpperCase(),userAccount: userAccount, auditId:auditId,cloudProvider: cloudProvider, date: date, user: user, region: region, action:action.toString(),applicationType: applicationType.toString(),status:status.toString()  )
		try{
			if (!cloudUsageTracker.save()) {
				cloudUsageTracker.errors.each { println it }
			}
		} catch (Exception e) {
			e.printStackTrace();
		}


	}
}
