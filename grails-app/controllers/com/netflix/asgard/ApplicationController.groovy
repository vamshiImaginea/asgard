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

import grails.converters.JSON
import grails.converters.XML

import org.apache.commons.collections.Bag
import org.apache.commons.collections.HashBag
import org.jclouds.ec2.domain.IpPermission
import org.jclouds.ec2.domain.SecurityGroup

import com.google.common.collect.Lists
import com.netflix.asgard.model.ApplicationInstance
import com.netflix.asgard.model.MonitorBucketType
import com.netflix.asgard.model.Owner

class ApplicationController {

	def discoveryService
	def applicationService
	def cloudReadyService
	def ec2Service
	def configService

	static allowedMethods = [save: 'POST', update: 'POST', delete: 'POST', securityUpdate: 'POST']

	static editActions = ['security']

	def index = { redirect(action: 'list', params: params) }

	static String[] typeList = [
		'Standalone Application',
		'Web Application',
		'Web Service'
	]

	def create = {
		[
			typeList: typeList,
			isChaosMonkeyActive: cloudReadyService.isChaosMonkeyActive()
		]
	}


	def show = {
		String name = params.name ?: params.id
		UserContext userContext = UserContext.of(request)
		Collection<ApplicationInstance> appIntances   = discoveryService.getAppInstances(userContext, name);
		def	application =  Application.find { applicationName == name }


		if (!appIntances) {
			Requests.renderNotFound('Application', name, this)
		} else {
			if (log.debugEnabled) {
				log.debug "In show, name=${app.name} type=${app.type} description=${app.description}"
			}

			Map<String,Collection<ApplicationInstance>> appToInstances =[:]

			appIntances.each{ApplicationInstance instance ->
				if(!appToInstances.get(instance.appName)){
					appToInstances.put(instance.appName, Lists.newArrayList())
				}
				name=instance.appName
				appToInstances.get(instance.appName).add(instance)
			}
			SecurityGroup appSecurityGroup = ec2Service.getSecurityGroup(userContext, name)
			boolean isChaosMonkeyActive = cloudReadyService.isChaosMonkeyActive(userContext.region)
			request.alertingServiceConfigUrl = configService.alertingServiceConfigUrl
			securities: ec2Service.getSecurityGroupsForApp(userContext, name)
			def details = [
				app: appToInstances,
				name: name,
				application : application,
				securities: ec2Service.getSecurityGroupsForApp(userContext, name),
				appSecurityGroup: appSecurityGroup,
				isChaosMonkeyActive: isChaosMonkeyActive,
				chaosMonkeyEditLink: cloudReadyService.constructChaosMonkeyEditLink(userContext.region, name)
			]
			withFormat {
				html { return details }
				xml { new XML(details).render(response) }
				json { new JSON(details).render(response) }
			}
		}
	}


	def list = {
		UserContext userContext = UserContext.of(request)
		Collection<ApplicationInstance> appIntances = discoveryService.getAppInstances(userContext)
		Bag instanceCountsPerAppName = instanceCountsPerAppName(userContext)
		Map<String,AppRegistration> appToInstances =[:]
		List<Application> applications = Application.getAll();

		applications.each{Application instance ->
			if(!appToInstances.get(instance.applicationName)){
				appToInstances.put(instance.applicationName,new AppRegistration(
						name:instance.applicationName, group:instance.groupName, type:instance.type,
						description:instance.description, owner:instance.owner, email:instance.ownerEmail, monitorBucketType:instance.monitorBucketType,
						createTime: new Date(Long.parseLong(instance.createdTime)),
						updateTime: new Date(Long.parseLong(instance.updatedTime))
						))
			}
		}
		appIntances.each{ApplicationInstance instance ->
			if(!appToInstances.get(instance.appName)){
				appToInstances.put(instance.appName,new AppRegistration(
						name:instance.appName
						))
			}
		}

		List<String> terms = Requests.ensureList(params.id).collect { it.split(',') }.flatten()
		Set<String> lowercaseTerms = terms*.toLowerCase() as Set
		if (terms) {
			appIntances = appIntances.findAll { ApplicationInstance app ->
				app.appName.toLowerCase() in lowercaseTerms ||
						app.owner?.toLowerCase() in lowercaseTerms ||
						app.email?.toLowerCase() in lowercaseTerms
			}
		}
		withFormat {
			html {
				[
					applications: appToInstances,
					terms: terms,
					instanceCountsPerAppName: instanceCountsPerAppName
				]
			}
			xml { new XML(appIntances).render(response) }
			json { new JSON(appIntances).render(response) }
		}
	}

	private Bag instanceCountsPerAppName(UserContext userContext) {
		Collection<ApplicationInstance> discInstances = discoveryService.getAppInstances(userContext)
		List<String> appInstanceNames = discInstances*.appName
		Bag instanceCountsPerAppName = new HashBag()
		appInstanceNames.each { instanceCountsPerAppName.add(it) }
		instanceCountsPerAppName
	}

	def save = { ApplicationCreateCommand cmd ->
		if (cmd.hasErrors()) {
			chain(action: 'create', model: [cmd: cmd], params: params)
		} else {
			String name = params.name
			UserContext userContext = UserContext.of(request)
			String group = params.group
			String type = params.type
			String desc = params.description
			String owner = params.owner
			String email = params.email
			String monitorBucketTypeString = params.monitorBucketType
			boolean enableChaosMonkey = params.chaosMonkey == 'enabled'
			MonitorBucketType bucketType = Enum.valueOf(MonitorBucketType, monitorBucketTypeString)
			CreateApplicationResult result = applicationService.createRegisteredApplication(userContext, name, group,
					type, desc, owner, email, bucketType, enableChaosMonkey)
			flash.message = result.toString()
			if (result.succeeded()) {
				redirect(action: 'show', params: [id: name])
			} else {
				chain(action: 'create', model: [cmd: cmd], params: params)
			}
		}
	}

	def edit = {
		UserContext userContext = UserContext.of(request)
		String name = params.name ?: params.id
		log.debug "Edit App: ${name}"
		def app = applicationService.getRegisteredApplication(userContext, name)
		['app': app, 'typeList': typeList]
	}

	def update = {
		String name = params.name
		UserContext userContext = UserContext.of(request)
		String group = params.group
		String type = params.type
		String desc = params.description
		String owner = params.owner
		String email = params.email
		String monitorBucketTypeString = params.monitorBucketType
		try {
			MonitorBucketType bucketType = Enum.valueOf(MonitorBucketType, monitorBucketTypeString)
			applicationService.updateRegisteredApplication(userContext, name, group, type, desc, owner, email,
					bucketType)
			flash.message = "Application '${name}' has been updated."
		} catch (Exception e) {
			flash.message = "Could not update Application: ${e}"
		}
		redirect(action: 'show', params: [id: name])
	}

	def delete = {
		String name = params.name
		UserContext userContext = UserContext.of(request)
		log.info "Delete App: ${name}"
		try {
			applicationService.deleteRegisteredApplication(userContext, name)
			flash.message = "Application '${name}' has been deleted."
		} catch (ValidationException ve) {
			flash.message = "Could not delete Application: ${ve.message}"
			redirect(action: 'show', params: [id: name])
			return
		} catch (Exception e) {
			flash.message = "Could not delete Application: ${e}"
		}
		redirect(action: 'list')
	}


	def owner = {
		UserContext userContext = UserContext.of(request)
		List<AppRegistration> apps = applicationService.getRegisteredApplications(userContext)
		Bag groupCountsPerAppName = groupCountsPerAppName(userContext)
		Bag instanceCountsPerAppName = instanceCountsPerAppName(userContext)
		Map<String, Owner> ownerNamesToOwners = [:]
		for (AppRegistration app in apps) {
			String ownerName = app.owner
			if (ownerNamesToOwners[ownerName] == null) {
				ownerNamesToOwners[ownerName] = new Owner(name: ownerName)
			}
			Owner owner = ownerNamesToOwners[ownerName]
			owner.emails << app.email
			owner.appNames << app.name
			owner.autoScalingGroupCount += groupCountsPerAppName.getCount(app.name)
			owner.instanceCount += instanceCountsPerAppName.getCount(app.name)
		}
		// Sort by number of instances descending, then by number of auto scaling groups descending
		List<Owner> owners = (ownerNamesToOwners.values() as List).
				sort { -1 * it.autoScalingGroupCount }.sort { -1 * it.instanceCount }
		withFormat {
			html { [owners: owners] }
			xml { new XML(owners).render(response) }
			json { new JSON(owners).render(response) }
		}
	}
	def security = {
		String name = params.name
		String securityGroupId = params.securityGroupId
		UserContext userContext = UserContext.of(request)
		AppRegistration app = applicationService.getRegisteredApplication(userContext, name)
		if (!app) {
			flash.message = "Application '${name}' not found."
			redirect(action: 'list')
			return
		}
		SecurityGroup group = ec2Service.getSecurityGroup(userContext, securityGroupId)
		if (!group) {
			flash.message = "Could not retrieve or create Security Group '${name}'"
			redirect(action: 'list')
			return
		}
		[
			app: app,
			name: name,
			group: group,
			groups: ec2Service.getSecurityGroupOptionsForSource(userContext, group)
		]
	}
	def securityUpdate = {
		String name = params.name
		UserContext userContext = UserContext.of(request)
		List<String> selectedGroups = Requests.ensureList(params.selectedGroups)
		SecurityGroup group =ec2Service.getSecurityGroup(userContext, name)
		updateSecurityEgress(userContext, group, selectedGroups, params)
		flash.message = "Successfully updated access for Application '${name}'"
		redirect(action: 'show', params: [id: name])
	}

	// Security Group permission updating logic

	private void updateSecurityEgress(UserContext userContext, SecurityGroup srcGroup, List<String> selectedGroups,
			Map portMap) {
		awsEc2Service.getSecurityGroups(userContext).each { SecurityGroup targetGroup ->
			boolean wantAccess = selectedGroups.any { it == targetGroup.groupName } &&
			portMap[targetGroup.groupName] != ''
			String wantPorts = wantAccess ? portMap[targetGroup.groupName] : null
			List<IpPermission> wantPerms = ec2Service.permissionsFromString(wantPorts)
			ec2Service.updateSecurityGroupPermissions(userContext, targetGroup, srcGroup, wantPerms)
		}
	}

}

class ApplicationCreateCommand {

	def cloudReadyService

	String name
	String email
	String type
	String description
	String owner
	String chaosMonkey
	boolean requestedFromGui

	static constraints = {
		name(nullable: false, blank: false, size: 1..Relationships.APPLICATION_MAX_LENGTH,
		validator: { value, command ->
			if (!Relationships.checkName(value)) {
				return "application.name.illegalChar"
			}
			if (Relationships.usesReservedFormat(value)) {
				return "name.usesReservedFormat"
			}
		})
		email(nullable: false, blank: false, email: true)
		type(nullable: false, blank: false)
		description(nullable: false, blank: false)
		owner(nullable: false, blank: false)
		chaosMonkey(nullable: true, validator: { value, command ->
			if (!command.chaosMonkey) {
				boolean isChaosMonkeyChoiceNeglected = command.cloudReadyService.isChaosMonkeyActive() &&
						command.requestedFromGui
				if (isChaosMonkeyChoiceNeglected) {
					return 'chaosMonkey.optIn.missing.error'
				}
			}
		})
	}
}
