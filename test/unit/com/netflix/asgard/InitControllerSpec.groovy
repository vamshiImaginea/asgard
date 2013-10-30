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

import grails.test.mixin.TestFor
import spock.lang.Specification
import spock.lang.Unroll

@TestFor(InitController)
class InitControllerSpec extends Specification {

    static final String SAMPLE_ACCESS_ID = 'AKIAII3BM5ENKCZEI4KQ'
    static final String SAMPLE_SECRET_KEY = 'yHxG2v/AuzKTKWSMT/JgqtlYGrpxdZggmV4Nxl9d'

    def configService = Mock(ConfigService)
    def initService = Mock(InitService)

    def setup() {
        TestUtils.setUpMockRequest()
        mockForConstraintsTests(InitializeCommand)
        controller.initService = initService
        controller.configService = configService
    }

    def 'should create config file'() {
        InitializeCommand command = new InitializeCommand(accessId: SAMPLE_ACCESS_ID, secretKey: SAMPLE_SECRET_KEY,
            accountNumber: '1111-2222-3333', cloudService:'aws')
        configService.getAsgardHome() >> 'asgardHomeDir'

        when:
        controller.save(command)

        then:
        '/home' == response.redirectUrl
        'Created Asgard configuration file at asgardHomeDir/Config.groovy.' == controller.flash.message
        1 * initService.writeConfig(_)
    }

    def 'should redirct with flash message for IOException'() {
        InitializeCommand command = new InitializeCommand(accessId: SAMPLE_ACCESS_ID, secretKey: SAMPLE_SECRET_KEY, cloudService:'aws')

        when:
        controller.save(command)

        then:
        'AWS Amazon Credentials are not provided' == controller.flash.message
        '/init/index' == response.redirectUrl
    }

  

    @Unroll("save should throw exception for AWS when accessId is #accessId #secretKey #accountNumber")
    def 'aws credentials contraints'() {
        when:
        InitializeCommand command = new InitializeCommand(accessId: accessId, secretKey: secretKey,
            accountNumber: accountNumber, cloudService:'aws')
        controller.save(command)

        then:
        'AWS Amazon Credentials are not provided' == controller.flash.message

        where:

        accessId         | secretKey | accountNumber
        null             | null      | null   
		'access'         | 'secret'  | null
		''               | ''        | null
		'access'         | null      | 'account'
		'access'         | null      | null
		
    }
	
	@Unroll("hasErrors should return #valid when accessId is #accessId")
	def 'aws contraints'() {
		when:
		InitializeCommand command = new InitializeCommand(accessId: accessId, secretKey: SAMPLE_SECRET_KEY,
			accountNumber: '111122223333', cloudService:'aws')
		command.validate()

		then:
		command.hasErrors() != valid

		where:

		accessId         | valid
		'accessId'       | false
		''               | true
		SAMPLE_ACCESS_ID | true
	}


    @Unroll("hasErrors should return #valid when secrectKey is #secretKey")
    def 'secretKey contraints'() {
        when:
        InitializeCommand command = new InitializeCommand(accessId: SAMPLE_ACCESS_ID, secretKey: secretKey,
            accountNumber: '111122223333')
        command.validate()

        then:
        command.hasErrors() != valid

        where:

        secretKey         | valid
        'secretKeyId'     | false
        ''                | true
        SAMPLE_SECRET_KEY | true
    }

    @Unroll("hasErrors should return #valid when accountNumber is #accountNumber")
    def 'accountNumber constraints'() {
        when:
        InitializeCommand command = new InitializeCommand(accessId: SAMPLE_ACCESS_ID, secretKey: SAMPLE_SECRET_KEY,
            accountNumber: accountNumber,cloudService:'aws')
        command.validate()

        then:
        command.hasErrors() != valid

        where:

        accountNumber    | valid
        '1111-2222-3333' | true
        '111122223333'   | true
        ''               | true
        'aaaa'           | false
        '1111222233334'  | false
    }
}
