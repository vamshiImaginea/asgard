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

import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder


/**
 * Controller that handles all user requests if there is no Config.groovy in ASGARD_HOME
 */
class InitController {

	def initService
	def configService

	static allowedMethods = [save: 'POST',show: 'GET']


	def index = { InitializeCommand cmd ->
		[asgardHome: configService.asgardHome]
		File asgardHomeDir = new File(configService.asgardHome)
		asgardHomeDir.mkdirs()
		if (!asgardHomeDir.exists()) {
			return
		}

		File configFile = new File(configService.asgardHome, 'Config.groovy')
		if(!configFile.exists()) {
			return
		}
		String cloudProvider = params.get('cloudProvider')
		
		if(cloudProvider) {
			cmd.userName = configService.getUserName(cloudProvider)
			cmd.apiKey =  configService.getApiKey(cloudProvider)
			cmd.endPoint = configService.getEndPoint(cloudProvider)
			cmd.accountNo = configService.getAccount(cloudProvider)
			cmd.cloudProvider = cloudProvider
			render(view: 'index', model: [params: cmd])
		}
	}


	/**
	 * Creates the Config.groovy file from the supplied parameters and redirects to the home page if successful
	 */
	def save = { InitializeCommand cmd ->

		if (cmd.hasErrors()) {

			render(view: 'index', model: [cmd: cmd])
			return
		}

		try {
			initService.writeConfig(cmd.toConfigObject(configService.userConfig))
		} catch (Exception ioe) {
			flash.message = ioe.message
			redirect(action: 'index')
			return
		}
		flash.message = "Created Asgard configuration file at ${configService.asgardHome}/Config.groovy."
		redirect(controller: 'home')
	}
}

class InitializeCommand {
	String userName
	String apiKey
	String accountNumber
	String endPoint
	String cloudProvider
	String accountNo
	boolean showPublicAmazonImages
	

	ConfigObject toConfigObject(ConfigObject userConf) {
		ConfigObject rootConfig = new ConfigObject()
		ConfigObject grailsConfig = new ConfigObject()
		ConfigObject userCofig = userConf
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		String loggedInUser = auth.getName();
		rootConfig[loggedInUser] = userCofig
		userCofig['grails'] = grailsConfig
		if(!loggedInUser || !apiKey) {
			throw new Exception("Cloud Credentials are not provided")
		}
		ConfigObject cloud = new ConfigObject()
		String accountNumber = accountNo.replace('-','')
		cloud['userName'] = userName
		cloud['apiKey'] = apiKey.trim()
		cloud['accountNumber'] = accountNumber

		if (cloudProvider.equals("aws")) {
			if( !accountNo ) {
				throw new Exception("AWS Amazon Account No  not provided")
			}
			cloud['publicResourceAccounts'] = showPublicAmazonImages ? ['amazon']: []
		}else if(cloudProvider.equals("openstack") ) {
			cloud['endPoint'] = endPoint.trim()
		}
		userCofig[cloudProvider] = cloud
		grailsConfig['currentActiveService'] = cloudProvider
		grailsConfig['accountName'] = 'prod'
		rootConfig
	}
}