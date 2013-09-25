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

import com.amazonaws.services.opsworks.model.Command;
import com.netflix.asgard.push.CommonPushOptions;
import com.perforce.p4java.server.CmdSpec;
import com.sun.org.apache.bcel.internal.generic.NEW;

import org.springframework.web.servlet.ModelAndView


/**
 * Controller that handles all user requests if there is no Config.groovy in ASGARD_HOME
 */
class InitController {

	def initService
	def configService

	static allowedMethods = [save: 'POST',show: 'GET']


	def index = {
		InitializeCommand cmd ->
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


		if(params.get('cloudService')) {
			def config = new ConfigSlurper().parse(configFile.toURL())
			cmd.accessId = config.secret.accessId
			cmd.secretKey = config.secret.secretKey
			cmd.accountNumber = config.secret.accountNumber
			cmd.openStackUrl = config.openstack.endpoint
			cmd.openStackPassword = config.openstack.passwd
			cmd.openStackUsername = config.openstack.username

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
			initService.writeConfig(cmd.toConfigObject())
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
	String accessId
	String secretKey
	String accountNumber
	String openStackUrl
	String openStackUsername
	String openStackPassword
	String cloudService
	boolean showPublicAmazonImages
	static constraints = {

		/*	 accessId(nullable: true, blank: false, matches: /[A-Z0-9]{20}/)
		 secretKey(nullable: true, blank: false, matches: /[A-Za-z0-9\+\/]{40}/)
		 accountNumber(nullable: true, blank: false, matches: /\d{4}-?\d{4}-?\d{4}/)*/

	}

	ConfigObject toConfigObject() {
		if (cloudService.equals("aws")) {

			if(!accessId || !accountNumber || !secretKey) {
				throw new Exception("AWS Amazon Credentials are not provided")
			}
			ConfigObject rootConfig = new ConfigObject()
			ConfigObject grailsConfig = new ConfigObject()
			rootConfig['grails'] = grailsConfig
			String accountNumber = accountNumber.replace('-','')
			grailsConfig['awsAccounts'] =  [accountNumber]
			grailsConfig['awsAccountNames'] = [(accountNumber): 'prod']
			grailsConfig['currentActiveService'] = cloudService
			ConfigObject secretConfig = new ConfigObject()
			rootConfig['secret'] = secretConfig
			secretConfig['accessId'] = accessId.trim()
			secretConfig['secretKey'] = secretKey.trim()
			secretConfig['accountNumber'] = accountNumber
			ConfigObject cloudConfig = new ConfigObject()
			rootConfig['cloud'] = cloudConfig
			cloudConfig['accountName'] = 'prod'
			cloudConfig['publicResourceAccounts'] = showPublicAmazonImages ? ['amazon'] : []
			rootConfig
		}else {
		if(!openStackUrl || !openStackUsername || !openStackPassword) {
				throw new Exception("OpenStack Credentials are not provided")
			}
			ConfigObject rootConfig = new ConfigObject()
			ConfigObject grailsConfig = new ConfigObject()
			rootConfig['grails'] = grailsConfig
			ConfigObject secretConfig = new ConfigObject()
			rootConfig['openstack'] = secretConfig
			secretConfig['passwd'] = openStackPassword.trim()
			secretConfig['username'] = openStackUsername.trim()
			secretConfig['endpoint'] = openStackUrl.trim()
			grailsConfig['currentActiveService'] = cloudService
			ConfigObject cloudConfig = new ConfigObject()
			rootConfig['cloud'] = cloudConfig
			cloudConfig['accountName'] = 'prod'
			rootConfig
		}




	}
}