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

class RegionTests extends GroovyTestCase {

    void testWithCode() {
        assert new Region(code:'us-west-1') == Region.withCode('us-west-1')
        assert 'us-west-1' == Region.withCode('us-west-1').code
        assert 'eu-west-1' == Region.withCode('eu-west-1').code
        assertNotNull Region.withCode('us-east')
        assertNotNull Region.withCode('blah')
        assertNotNull Region.withCode('')
        assertNotNull Region.withCode(null)
        assertNotNull Region.withCode('  us-east-1  ')
    }

  
}
