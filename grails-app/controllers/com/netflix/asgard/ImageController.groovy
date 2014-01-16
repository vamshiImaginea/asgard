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

import com.netflix.asgard.model.InstanceTypeData
import com.netflix.asgard.model.JanitorMode
import com.netflix.asgard.model.MassDeleteRequest
import com.netflix.grails.contextParam.ContextParam

import grails.converters.JSON
import grails.converters.XML

import org.codehaus.groovy.grails.web.binding.DataBindingUtils
import org.codehaus.groovy.grails.web.json.JSONElement
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;

@ContextParam('region')
class ImageController {

    def providerEc2Service
	def providerComputeService
    def imageService
    def instanceTypeService
    def mergedInstanceGroupingService
    def taskService
    def grailsApplication
	def configService
	def launchTemplateService
	def applicationAuditService

    def static allowedMethods = [update: 'POST', delete: ['POST', 'DELETE'], launch: 'POST', addTag: 'POST',
            addTags: 'POST', removeTag: ['POST', 'DELETE'], removeTags: ['POST', 'DELETE'], removeAllTags: 'DELETE',
            massDelete: ['POST', 'DELETE']]

    def static editActions = ['prelaunch']

    def index = { redirect(action: 'list', params:params) }

    def list = {
        UserContext userContext = UserContext.of(request)
        Collection<Image> images = []
        Set<String> packageNames = Requests.ensureList(params.id).collect { it.split(',') }.flatten() as Set<String>
        if (packageNames) {
            images = packageNames.collect { providerComputeService.getImagesForPackage(userContext, it) }.flatten()
        } else {
            images = providerComputeService.getImagesForPackage(userContext, '')
        }
        images = images.sort { it.description.toLowerCase() }
        List<String> accounts = configService.accounts
        withFormat {
            html { [images: images, packageNames: packageNames, accounts: accounts] }
            xml { new XML(images).render(response) }
            json { new JSON(images).render(response) }
        }
    }

    def show = {
        UserContext userContext = UserContext.of(request)
        String imageId = EntityType.image.ensurePrefix(params.imageId ?: params.id)
		imageId=URLDecoder.decode(imageId,'UTF-8');
		imageId = imageId.contains('/') && configService.getCloudProvider() != Provider.RACKSPACE ?imageId.substring(imageId.indexOf('/')+1):imageId
		log.info 'show details for '+ imageId 
        Image image = imageId ? providerComputeService.getImage(userContext, imageId) : null
        image?.tags?.sort { it.key }
        if (!image) {
            Requests.renderNotFound('Image', imageId, this)
        } else {
            List<String> launchUsers = []
            try { launchUsers = providerComputeService.getImageLaunchers(userContext, image.id) }
            catch (Exception ignored) { /* We may not own the image, so ignore failures here */ }
            /*String snapshotId = image.blockDeviceMappings.findResult { it.ebs?.snapshotId }*/
            String ownerId = image.userMetadata.get("owner")
           String accounts = configService.account
            Map details = [
                    image: image,
                   /* snapshotId: snapshotId,*/
                    launchUsers: launchUsers,
                    accounts: accounts,
                    accountName: ownerId
            ]
            withFormat {
                html { return details }
                xml { new XML(details).render(response) }
                json { new JSON(details).render(response) }
            }
        }
    }

    def edit = {
        UserContext userContext = UserContext.of(request)
        def launchUsers = []
        def imageId = EntityType.image.ensurePrefix(params.imageId ?: params.id)
		imageId=URLDecoder.decode(imageId,'UTF-8');
		log.info 'changing image attributes for ' +imageId
        try {
            launchUsers = providerComputeService.getImageLaunchers(userContext, imageId)
        }
        catch (Exception e) {
            flash.message = "Unable to modify ${imageId} on this account because ${e}"
            redirect(action: 'show', params: params)
        }
        ['image' : providerComputeService.getImage(userContext, imageId),
         'launchPermissions' : launchUsers,
         'accounts' : grailsApplication.config.grails.accountName]
    }

    def update = {
        def imageId = EntityType.image.ensurePrefix(params.imageId)
        UserContext userContext = UserContext.of(request)
		imageId=URLDecoder.decode(imageId,'UTF-8');
        List<String> launchPermissions = (params.launchPermissions instanceof String) ? [ params.launchPermissions ] : params.launchPermissions?: []
        try {
            providerEc2Service.setImageLaunchers(userContext, imageId, launchPermissions)
            flash.message = "Image '${imageId}' has been updated."
        } catch (Exception e) {
            flash.message = "Could not update Image: ${e}"
        }
        redirect(action: 'show', params: [id: URLEncoder.encode(imageId,'UTF-8')])
    }

    def delete = { ImageDeleteCommand cmd ->
        if (cmd.hasErrors()) {
            chain(action: 'show', model: [cmd: cmd], params: params) // Use chain to pass both the errors and the params
        } else {
            UserContext userContext = UserContext.of(request)
            String imageId = params.id
			imageId=URLDecoder.decode(imageId,'UTF-8');
            try {
                Image image = providerComputeService.getImage(userContext, imageId)
                String packageName = image.getType()
	            imageService.deleteImage(userContext, imageId)
                flash.message = "Image '${imageId}' has been deleted."
                redirect(action: 'list', params: [id: packageName])
            } catch (Exception e) {
                flash.message = "Could not delete image: ${e}"
                redirect(action: 'show', params: [id: URLEncoder.encode(imageId,'UTF-8')])
            }
        }
    }

    def prelaunch = {
        UserContext userContext = UserContext.of(request)
        String imageId = EntityType.image.ensurePrefix(params.id)
		imageId=URLDecoder.decode(imageId,'UTF-8')		
        Collection<InstanceTypeData> instanceTypes = instanceTypeService.getInstanceTypes(userContext)
        [
                 'imageId' : imageId,
                 'instanceType' : '',
                 'instanceTypes' : instanceTypes,
                 'securityGroups' : providerEc2Service.getEffectiveSecurityGroups(userContext),
                 'zone' : 'any',
                 'zoneList' : providerEc2Service.getRecommendedAvailabilityZones(userContext)
        ]
    }

    def launch = {

        String message = ''
        Closure output = {}
        List<String> instanceIds = []
        List<String> spotInstanceRequestIds = []

        try {
            UserContext userContext = UserContext.of(request)
            String pricing = params.pricing
            String pricingMissingMessage = 'Missing required parameter pricing=spot or pricing=ondemand'
            Check.condition(pricingMissingMessage, { pricing in ['ondemand', 'spot'] })
            String imageId = EntityType.image.ensurePrefix(params.imageId)
			imageId=URLDecoder.decode(imageId,'UTF-8');
            String owner = Check.notEmpty(params.owner as String, 'owner')
            String zone = params.zone
            String instanceType = params.instanceType
            List<String> rawSecurityGroups = Requests.ensureList(params.selectedGroups)
            Collection<String> securityGroups = launchTemplateService.includeDefaultSecurityGroups(rawSecurityGroups)
            Integer count = 1
            if (pricing == 'ondemand') {
                Set<NodeMetadata> launchedInstances = imageService.runOnDemandInstances(userContext, imageId, count,
                        securityGroups, instanceType, zone, owner)
                instanceIds = launchedInstances*.id
                message = "Image '${imageId}' has been launched as ${instanceIds}"
                output = { instances { instanceIds.each { instance(it) } } }
            }
			applicationAuditService.addAuditData(userContext, AuditApplicationType.INSTANCE,Action.CREATE,Status.SUCCESS)
		} catch (Exception e) {
			applicationAuditService.addAuditData(userContext, AuditApplicationType.INSTANCE,Action.CREATE,Status.FAILURE)
			e.printStackTrace();
			message = "Could not launch Image: ${e}"
			output = { error(message) }
		}

        withFormat {
            form {
                flash.message = message
                Map redirectParams = [action: 'result']
                if (instanceIds) {
                    redirectParams = [controller: 'instance', action: 'show', id: URLEncoder.encode(instanceIds[0],'UTF-8')]
                } else if (spotInstanceRequestIds) {
                    redirectParams = [controller: 'spotInstanceRequest', action: 'show', id: spotInstanceRequestIds[0]]
                }
                redirect(redirectParams)
            }
            xml { render(contentType: 'application/xml', output) }
            json { render(contentType: 'application/json', output) }
        }
    }

    def result = { render view: '/common/result' }

    def references = {
        UserContext userContext = UserContext.of(request)
        String imageId = EntityType.image.ensurePrefix(params.imageId ?: params.id)
        Collection<NodeMetadata> instances = providerComputeService.getInstancesUsingImageId(userContext, imageId)
        Map result = [:]
        result['instances'] = instances.collect { it.instanceId }
        render result as JSON
    }

    def used = {
        UserContext userContext = UserContext.of(request)
        Collection<String> imageIdsInUse = imageService.getLocalImageIdsInUse(userContext)
        withFormat {
            html { render imageIdsInUse.toString() }
            xml { new XML(imageIdsInUse).render(response) }
            json { new JSON(imageIdsInUse).render(response) }
        }
    }

    /**
     * Adds or replaces a tag on the image. Expects the following params:
     *         imageId (in the POST data) or id (on URL) - the id of the image to tag
     *         name - the key of the tag to add or replace
     *         value - the value of the tag to add or replace
     */
    def addTag = {
        String imageId = params.imageId ?: params.id
        Check.notEmpty(imageId, 'imageId')
        performAddTags([imageId])
    }

    /**
     * Adds or replaces a tags on a set of images in batch. Expects the following params:
     *         imageIds - comma separated list of image ids to add or replace tags on
     *         name - the key of the tag to add or replace
     *         value - the value of the tag to add or replace
     */
    def addTags = {
        performAddTags(params.imageIds?.tokenize(','))
    }

    private def performAddTags(Collection<String> imageIds) {
        String name = params.name
        String value = params.value
        Check.notEmpty(name, 'name')
        Check.notEmpty(value, "value for ${name}")
        Check.notEmpty(imageIds, 'imageIds')
        Collection<String> prefixedImageIds = imageIds.collect { EntityType.image.ensurePrefix(it) }
        UserContext userContext = UserContext.of(request)
        ec2Service.createImageTags(userContext, prefixedImageIds, name, value)
        render "Tag ${name}=${value} added to image${imageIds.size() > 1 ? 's' : ''} ${prefixedImageIds.join(', ')}"
    }

    /**
     * Removes a tag from an image based on the key of the tag. Expects the following params:
     *         imageId (in the POST data) or id (on URL) - the id of the image to remove the tag on
     *         name - the key of the tag to remove on the image
     */
    def removeTag = {
        String imageId = params.imageId ?: params.id
        Check.notEmpty(imageId, 'imageId')
        performRemoveTags([imageId])
    }

    /**
     * Removes a tag from an image based on the key of the tag. Expects the following params:
     *         imageIds - comma separated list of image ids to remove tags on
     *         name - the key of the tag to remove on the image
     */
    def removeTags = {
        performRemoveTags(params.imageIds?.tokenize(','))
    }

    private void performRemoveTags(Collection<String> imageIds) {
        String name = params.name
        Check.notEmpty(name, 'name')
        Check.notEmpty(imageIds, 'imageIds')
        Collection<String> prefixedImageIds = imageIds.collect { EntityType.image.ensurePrefix(it) }
        UserContext userContext = UserContext.of(request)
        ec2Service.deleteImageTags(userContext, prefixedImageIds, name)
        render "Tag ${name} removed from image${imageIds.size() > 1 ? 's' : ''} ${imageIds.join(', ')}"
    }

    def replicateTags = {
        log.info 'image/replicateTags called. Starting unscheduled image tag replication.'
        imageService.replicateImageTags()
        render 'done'
    }

    def massDelete = {
        UserContext userContext = UserContext.of(request)
        MassDeleteRequest massDeleteRequest = new MassDeleteRequest()
        DataBindingUtils.bindObjectToInstance(massDeleteRequest, params)
        List<Image> deleted = imageService.massDelete(userContext, massDeleteRequest)

        String executeMessage = "Started deleting the following ${deleted.size()} images in ${userContext.region}:\n"
        String dryRunMessage = "Dry run mode. If executed, this job would delete ${deleted.size()} images in ${userContext.region}:\n"
        String initialMessage = JanitorMode.EXECUTE == massDeleteRequest.mode ? executeMessage : dryRunMessage
        String message = deleted.inject(initialMessage) { message, image -> message + image + '\n' }
        render "<pre>\n${message}</pre>\n"
    }

    def analyze = {
        UserContext userContext = UserContext.of(request)
        Collection<Image> allImages = providerComputeService.getAccountImages(userContext)
        List<Image> dateless = []
        List<Image> baseless = []
        List<Image> baselessInUse = []

        Set<String> amisInUse = new HashSet<String>()
        Map<String, List<MergedInstance>> imageIdsToInstanceLists = [:]
        List<MergedInstance> instances = mergedInstanceGroupingService.getMergedInstances(userContext, '')
        instances.each { MergedInstance instance ->
            if (instance.amiId) {
                String amiId = instance.amiId
                amisInUse << amiId

                List<MergedInstance> instancesForAmi = imageIdsToInstanceLists.get(amiId)
                if (instancesForAmi) {
                    instancesForAmi.add(instance)
                } else {
                    imageIdsToInstanceLists.put(amiId, [instance])
                }
            }
        }

        allImages.each { Image image ->
            // Look through all images and read descriptions looking for base AMIs and ancestors.
            if (!image.baseAmiId) { baseless << image }
            if (!image.creationTime) { dateless << image }
        }

        Map<String> deregisteredAmisToInstanceAsgs = [:]

        List<Image> inUseImages = []
        imageIdsToInstanceLists.keySet().each { String inUseAmiId ->
            Image inUseImage = allImages.find { Image im -> im.imageId == inUseAmiId }
            if (inUseImage) {
                inUseImages << inUseImage
            } else {
                deregisteredAmisToInstanceAsgs.put(inUseAmiId,
                    imageIdsToInstanceLists[inUseAmiId].collect {
                        MergedInstance inst-> new Expando('instance': inst, 'groupName': inst.autoScalingGroupName)
                    }
                )
            }
        }
        inUseImages = inUseImages.sort { it.baseAmiDate }

        Map<String, List<Image>> appVersionsToImageLists = [:]
        inUseImages.each { Image image ->
            if (image.appVersion) {
                List<Image> imageList = appVersionsToImageLists[image.appVersion] ?: []
                imageList << image
                appVersionsToImageLists[image.appVersion] = imageList
            }
        }
        appVersionsToImageLists = appVersionsToImageLists.sort().sort { a, b -> b.value.size() <=> a.value.size() }

        baseless.each { Image image ->
            if (imageIdsToInstanceLists.keySet().contains(image.imageId)) {
                baselessInUse << image
            }
        }

        Map details = [
                'dateless': dateless,
                'baseless': baseless,
                'baselessInUse': baselessInUse,
                'inUseImages': inUseImages,
                'appVersionsToImageLists': appVersionsToImageLists,
                'imageIdsToInstanceLists': imageIdsToInstanceLists,
                'deregisteredAmisToInstanceAsgs': deregisteredAmisToInstanceAsgs
        ]
        withFormat {
            html { details }
            xml { new XML(details).render(response) }
            json { new JSON(details).render(response) }
        }
    }

    def tagAmiLastReferencedTime = {
        UserContext userContext = UserContext.of(request)
        String imageTagMasterAccount = grailsApplication.config.cloud.imageTagMasterAccount
        if (grailsApplication.config.cloud.accountName == imageTagMasterAccount) {
            imageService.tagAmiLastReferencedTime(userContext)
            render 'Image last_referenced_time tagging started'
        } else {
            render "This operation can only be run in the ${imageTagMasterAccount} account which controls image tags"
        }
    }
}

class ImageDeleteCommand {
    String id
    ProviderEc2Service ec2Service
    RestClientService restClientService
    def grailsApplication
	def configService

    @SuppressWarnings("GroovyAssignabilityCheck")
    static constraints = {
        id(nullable: false, blank: false, validator: { String value, ImageDeleteCommand command ->
			if(command.configService.cloudProvider == Provider.AWS){
            UserContext userContext = UserContext.of(Requests.request)
            String promotionTargetServer = command.grailsApplication.config.promote.targetServer
            String env = command.grailsApplication.config.cloud.accountName

            // If AMI is in use by a launch config or instance in the current region-env then report those references.
            Collection<String> instances = command.ec2Service.
                    getInstancesUsingImageId(userContext, value).collect { it.id }
            
            if (instances) {
                String reason = constructReason(instances)
                return ['image.imageId.used', value, env, reason]
            } else if (promotionTargetServer) {
                // If the AMI is not in use on master server, check promoted data.
                String url = "${promotionTargetServer}/${userContext.region}/image/references/${value}"
                JSONElement json = command.restClientService.getAsJson(url)
                if (json == null) {
                    return ['image.imageId.prodInaccessible', value, url]
                }
                Collection<String> remoteInstances = json.instances
                Collection<String> remoteLaunchConfigurations = json.launchConfigurations
                if (remoteInstances || remoteLaunchConfigurations) {
                    String reason = constructReason(remoteInstances, remoteLaunchConfigurations)
                    return ['image.imageId.used', value, 'prod', reason]
                }
            }
            null
			}
        })
        
    }

    static constructReason(Collection<String> instanceIds ) {
        instanceIds ? "instance${instanceIds.size() == 1 ? '' : 's'} $instanceIds" :""
    }
}
