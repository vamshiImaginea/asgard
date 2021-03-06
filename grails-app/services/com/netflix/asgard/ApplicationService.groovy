/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard

import java.util.Date;

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.simpledb.AmazonSimpleDB
import com.amazonaws.services.simpledb.model.CreateDomainRequest
import com.amazonaws.services.simpledb.model.DeleteAttributesRequest
import com.amazonaws.services.simpledb.model.Item
import com.amazonaws.services.simpledb.model.PutAttributesRequest
import com.amazonaws.services.simpledb.model.ReplaceableAttribute
import com.amazonaws.services.simpledb.model.SelectRequest
import com.amazonaws.services.simpledb.model.SelectResult
import com.netflix.asgard.cache.CacheInitializer
import com.netflix.asgard.model.MonitorBucketType

import org.joda.time.DateTime

class ApplicationService  {

	static transactional = false


	def grailsApplication
	def ec2Service
	def configService
	def mergedInstanceGroupingService
	def taskService

	CreateApplicationResult createRegisteredApplication(UserContext userContext, String nameInput, String group,
			String type, String description, String owner, String email, MonitorBucketType monitorBucketType,
			boolean enableChaosMonkey) {
		String name = nameInput.toLowerCase()
		CreateApplicationResult result = new CreateApplicationResult()
		result.appName = name
		if (getRegisteredApplication(userContext, name)) {
			result.appCreateException = new IllegalStateException("Can't add Application ${name}. It already exists.")
			return result
		}
		String nowEpoch = new DateTime().millis as String
		String creationLogMessage = "Create registered app ${name}, type ${type}, owner ${owner}, email ${email}"

		taskService.runTask(userContext, creationLogMessage, { task ->
			try {
				def app = new Application(applicationName : nameInput,groupName :group,type :type,ownerEmail: email,description:description,owner:owner,
				createdTime:nowEpoch,updatedTime:nowEpoch,chaosMonkey:enableChaosMonkey,monitorBucketType:monitorBucketType.toString())
				if (!app.save(flush:true)) {
					app.errors.each { println it }
				}
				result.appCreated = true
			} catch (Exception e) {
				result.appCreateException = e
				e.printStackTrace();
			}
			if (enableChaosMonkey) {
				task.log("Enabling Chaos Monkey for ${name}.")
				result.cloudReadyUnavailable = !cloudReadyService.enableChaosMonkeyForApplication(name)
			}
		}, Link.to(EntityType.application, name))
		getRegisteredApplication(userContext, name)
		result
	}


	Application getRegisteredApplication(UserContext userContext, String nameInput) {
		if (!nameInput) {
			return null
		}
		assert !(nameInput.contains("'")) // Simple way to avoid SQL injection
		Application.find { applicationName == nameInput }
	}

	void updateRegisteredApplication(UserContext userContext, String name, String group, String type, String desc,
			String owner, String email, MonitorBucketType bucketType) {
		String nowEpoch = new DateTime().millis as String

		taskService.runTask(userContext,
				"Update registered app ${name}, type ${type}, owner ${owner}, email ${email}", { task ->
					def app = Application.find { applicationName == name }
					app.groupName =group
					app.type =type
					app.ownerEmail =email
					app.ownerEmail =desc
					app.owner = owner
					app.updatedTime =nowEpoch
					app.monitorBucketType = bucketType.toString()
					if (!app.save(flush:true)) {
						app.errors.each { println it }
					}
				}, Link.to(EntityType.application, name))
		getRegisteredApplication(userContext, name)
	}

	void deleteRegisteredApplication(UserContext userContext, String name) {
		Check.notEmpty(name, "name")
		validateDelete(userContext, name)
		taskService.runTask(userContext, "Delete registered app ${name}", { task ->
			def app = Application.find { applicationName == name }
			app.delete();
		}, Link.to(EntityType.application, name))
		getRegisteredApplication(userContext, name)
	}

	private void validateDelete(UserContext userContext, String name) {
		List<String> objectsWithEntities = []

		if (ec2Service.getSecurityGroupsForApp(userContext, name)) {
			objectsWithEntities.add('Security Groups')
		}
		if (mergedInstanceGroupingService.getMergedInstances(userContext, name)) {
			objectsWithEntities.add('Instances')
		}

		if (objectsWithEntities) {
			String referencesString = objectsWithEntities.join(', ')
			String message = "${name} ineligible for delete because it still references ${referencesString}"
			throw new ValidationException(message)
		}
	}

	List<Application> getRegisteredApplications(UserContext userContext) {
		Application.all;
	}




}

/**
 * Records the results of trying to create an Application.
 */
class CreateApplicationResult {
	String appName
	Boolean appCreated
	Exception appCreateException
	Boolean cloudReadyUnavailable // Just a warning, does not affect success.

	String toString() {
		StringBuilder output = new StringBuilder()
		if (appCreated) {
			output.append("Application '${appName}' has been created. ")
		}
		if (appCreateException) {
			output.append("Could not create Application '${appName}': ${appCreateException}. ")
		}
		if (cloudReadyUnavailable) {
			output.append('Chaos Monkey was not enabled because Cloudready is currently unavailable. ')
		}
		output.toString()
	}

	Boolean succeeded() {
		appCreated && !appCreateException
	}
}

