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

import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.amazonaws.services.elasticloadbalancing.model.SourceSecurityGroup
import com.netflix.grails.contextParam.ContextParam
import grails.converters.JSON
import grails.converters.XML
import org.jclouds.ec2.domain.IpPermission
import org.jclouds.ec2.domain.SecurityGroup

@ContextParam('region')
class SecurityController {

    def providerEc2Service
    def configService
	def applicationService
	def cloudUsageTrackerService

    def static allowedMethods = [save: 'POST', update: 'POST', delete: 'POST']

    def index = { redirect(action: 'list', params: params) }

    def list = {
        UserContext userContext = UserContext.of(request)
        Set<String> appNames = Requests.ensureList(params.id).collect { it.split(',') }.flatten() as Set<String>
        Collection<SecurityGroup> securityGroups = providerEc2Service.getSecurityGroups(userContext)
        if (appNames) {
            securityGroups = securityGroups.findAll {
                appNames.contains(Relationships.appNameFromSecurityGroupName(it.name))
            }
        }
        securityGroups = securityGroups.sort { it.name.toLowerCase() }

        Map details = [securityGroups: securityGroups, appNames: appNames]
        withFormat {
            html { details }
            xml { new XML(details).render(response) }
            json { new JSON(details).render(response) }
        }
    }

    def show = {
        UserContext userContext = UserContext.of(request)
        def name = params.name ?: params.id
        SecurityGroup group = providerEc2Service.getSecurityGroup(userContext, name)
        if (null == group) {
            Requests.renderNotFound('Security Group', name, this)
            return
        }
    /*        Collection<Instance> instances = ec2Service.getInstancesWithSecurityGroup(userContext, group)*/
		
        def details = [
                group: group,
				app: applicationService.getRegisteredApplication(userContext, group.name),
                editable: providerEc2Service.isSecurityGroupEditable(group.name)          
              /*  instances: instances,*/
              
        ]
        withFormat {
            html { return details }
            xml { new XML(details).render(response) }
            json { new JSON(details).render(response) }
        }
    }

    def create = {
        UserContext userContext = UserContext.of(request)
        String name = params.id ?: params.name
        String description = ''
       List<Application> applications = []
        if (name) {
            Application app = applicationService.getRegisteredApplication(userContext, name)
            description = app?.description
        } else {
            applications = applicationService.getRegisteredApplications(userContext)
        }
        [
            applications: applications,
            //vpcIds: awsEc2Service.getVpcs(userContext)*.vpcId,
            //selectedVpcIds: params.selectedVpcIds,
            enableVpc: params.enableVpc,
            name: name,
            description: params.description ?: description,
        ]
    }

    def save = { SecurityCreateCommand cmd ->
        if (cmd.hasErrors()) {
            chain(action: 'create', model: [cmd: cmd], params: params) // Use chain to pass both the errors and the params
        } else {
            UserContext userContext = UserContext.of(request)
            String name = params.appName
            try {
                SecurityGroup securityGroup = providerEc2Service.getSecurityGroup(userContext, name)
                if (!securityGroup) {
					cloudUsageTrackerService.addAuditData(userContext, AuditApplicationType.SECURITY_GROUP,Action.CREATE,Status.SUCCESS)
                    securityGroup = providerEc2Service.createSecurityGroup(userContext, name, params.description, params.vpcId)
                    flash.message = "Security Group '${name}' has been created."
                } else {
                    flash.message = "Security Group '${name}' already exists."
                }
                redirect(action: 'show', params: [id: securityGroup.name])
            } catch (Exception e) {
			cloudUsageTrackerService.addAuditData(userContext, AuditApplicationType.SECURITY_GROUP,Action.CREATE,Status.FAILURE)
                flash.message = "Could not create Security Group: ${e}"
                chain(action: 'create', model: [cmd: cmd], params: params)
            }
        }
    }

    def edit = {
        UserContext userContext = UserContext.of(request)
        String id = params.id
        SecurityGroup group = providerEc2Service.getSecurityGroup(userContext, id)
        if (null == group) {
            Requests.renderNotFound('Security Group', id, this)
            return
        }
        [
                group: group,
                groups: providerEc2Service.getSecurityGroupOptionsForTarget(userContext, group),
                editable: providerEc2Service.isSecurityGroupEditable(group.name)
        ]
    }

    def update = {
        String name = params.name ?: params.id
        List<String> selectedGroups = Requests.ensureList(params.selectedGroups)
        UserContext userContext = UserContext.of(request)
        SecurityGroup securityGroup = providerEc2Service.getSecurityGroup(userContext, name)
        if (null != securityGroup) {
            if (providerEc2Service.isSecurityGroupEditable(securityGroup.name)) {
                try {
                    updateSecurityIngress(userContext, securityGroup, selectedGroups, params)
					cloudUsageTrackerService.addAuditData(userContext, AuditApplicationType.SECURITY_GROUP,Action.UPDATE,Status.SUCCESS)
                    flash.message = "Security Group '${securityGroup.name}' has been updated."
                    redirect(action: 'show', params: [id: securityGroup.name])
                } catch (Exception e) {
				cloudUsageTrackerService.addAuditData(userContext, AuditApplicationType.SECURITY_GROUP,Action.UPDATE,Status.FAILURE)
				  e.printStackTrace();
                    flash.message = "Could not update Security Group: ${e}"
                    redirect(action: 'edit', params: [id: securityGroup.name])
                }
            } else {
                flash.message = "Security group '${securityGroup.name}' should not be modified with this tool."
                redirect(action: 'list')
            }
        } else {
            flash.message = "Security Group '${name}' does not exist."
            redirect(action: 'result')
        }
    }

    private void updateSecurityIngress(UserContext userContext, SecurityGroup targetGroup, List<String> selectedGroups, Map portMap) {
        providerEc2Service.getSecurityGroups(userContext).each {srcGroup ->
			log.info 'portMap' +portMap
			log.info 'srcGroup' +srcGroup.name
            boolean wantAccess = selectedGroups.any {it == srcGroup.name} && portMap[srcGroup.name] != ''
            String wantPorts = wantAccess ? portMap[srcGroup.name] : null
            List<IpPermission> wantPerms = providerEc2Service.permissionsFromString(wantPorts)
            providerEc2Service.updateSecurityGroupPermissions(userContext, targetGroup, srcGroup, wantPerms)
        }
    }

    def delete = {
        UserContext userContext = UserContext.of(request)
        String name = params.name ?: params.id
        String msg
        try {
            SecurityGroup securityGroup = providerEc2Service.getSecurityGroup(userContext, name)
            if (null != securityGroup) {
                providerEc2Service.removeSecurityGroup(userContext, name, securityGroup.name)
				cloudUsageTrackerService.addAuditData(userContext, AuditApplicationType.SECURITY_GROUP,Action.DELETE,Status.SUCCESS)
                msg = "Security Group '${securityGroup.name}' has been deleted."
            } else {
                msg = "Security Group '${name}' does not exist."
            }
            flash.message = msg
            redirect(action: 'result')
        } catch (Exception e) {
		cloudUsageTrackerService.addAuditData(userContext, AuditApplicationType.SECURITY_GROUP,Action.DELETE,Status.FAILURE)
            flash.message = "Could not delete Security Group: ${e}"
            redirect(action: 'show', params: [id: name])
        }
    }

    def result = { render view: '/common/result' }
}

class SecurityCreateCommand {

    def applicationService

    String appName
    String detail

    static constraints = {

        appName(nullable: false, blank: false, validator: { value, command->
            UserContext userContext = UserContext.of(Requests.request)
            if (!Relationships.checkName(value)) {
                return 'application.name.illegalChar'
            }
            if (Relationships.usesReservedFormat(value)) {
                return 'name.usesReservedFormat'
            }
            if ("${value}-${command.detail}".length() > Relationships.GROUP_NAME_MAX_LENGTH) {
                return "The complete name cannot exceed ${Relationships.GROUP_NAME_MAX_LENGTH} characters"
            }
        })

        detail(nullable: true, validator: { value, command->
            if (value && !Relationships.checkDetail(value)) {
                return 'The detail must be empty or consist of alphanumeric characters and hyphens'
            }
            if (Relationships.usesReservedFormat(value)) {
                return 'name.usesReservedFormat'
            }
        })

    }
}
