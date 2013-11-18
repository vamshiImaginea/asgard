package com.netflix.asgard

import com.netflix.asgard.model.MonitorBucketType;

class Application {  
	String applicationName
	String groupName;
	String ownerEmail
	String description
	String type
	String owner
	String createdTime
	String updatedTime
	String chaosMonkey
	String monitorBucketType
	
    static constraints = {
		applicationName blank: false, unique: true
		description blank: false, unique: false
		owner blank: false, unique: false		
    }
}
