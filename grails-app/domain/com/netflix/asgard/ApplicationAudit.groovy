package com.netflix.asgard

import com.netflix.asgard.model.MonitorBucketType;

class ApplicationAudit {  
	String date
	String user
	String action
	String region
	String cloudProvider
	String applicationType
	String status	
	String userAccount
    static constraints = {
		applicationType blank: false, unique: false
		user blank: false, unique: false
		date blank: false, unique: false
		cloudProvider blank: false, unique: false
		userAccount blank: false, unique: false
		status blank: false, unique: false
		action blank: false, unique: false
    }
}
enum Action {
	
	CREATE,DELETE,UPDATE,REBOOT
}
enum Status{
	SUCCESS,FAILURE
}
enum AuditApplicationType {
	APPLICATION,INSTANCE,VOLUME,SECURITY_GROUP,SNAPSHOT
}