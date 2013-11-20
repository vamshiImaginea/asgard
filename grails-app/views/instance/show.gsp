<%--

    Copyright 2012 Netflix, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

--%>
<html>
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
  <meta name="layout" content="main"/>
  <title>${instance?.id} Instance</title>
</head>
<body>
  <div class="body">
    <h1>Instance Details</h1>
    <g:if test="${flash.message}">
      <div class="message">${flash.message}</div>
    </g:if>
    <g:if test="${instance}">
      <g:form class="validate">
        <input type="hidden" name="instanceId" value="${instance.id}"/>
        <input type="hidden" name="providerId" value="${instance.providerId}"/>
        <g:if test="${group?.desiredCapacity}">
          <div class="buttons">
            <h3>ASG Decrement:</h3>
            <g:buttonSubmit class="stop"
                    data-warning="Really Terminate instance ${instance.id} and decrement size of auto scaling group ${group.autoScalingGroupName} to ${group.desiredCapacity - 1}?"
                    action="terminateAndShrinkGroup"
                    value="Shrink ASG ${group.autoScalingGroupName} to Size ${group.desiredCapacity - 1} and Terminate Instance"
                    title="Terminate this instance and decrement the size of its auto scaling group." />
          </div>
        </g:if>
        <div class="buttons">
          <h3>Operating System:</h3>
          <g:buttonSubmit class="stop" data-warning="Really Terminate: ${instance.id}?"
                  action="terminate" value="Terminate Instance" title="Shut down and delete this instance." />
          <g:buttonSubmit class="shutdown" data-warning="Really Reboot: ${instance.id}?"
                  action="reboot" value="Reboot Instance" title="Restart the OS of the instance." />
          <g:link class="cli" action="raw" params="[instanceId: java.net.URLEncoder.encode(instance.id,'UTF-8')]" title="Display the operating system console output log.">Console Output (Raw)</g:link>
          <g:link class="userData" action="userDataHtml" params="[instanceId: java.net.URLEncoder.encode(instance.id,'UTF-8') ]" title="Display the user data executed by the instance on startup.">User Data</g:link>
        </div>
        <g:if test="${appNames}">
        <g:each var="app" in="${appNames}" > 
          <input type="hidden" name="appNames" value="${app}"/>
          </g:each>
          <div class="buttons">
            <h3>Eureka:</h3>
            <g:buttonSubmit class="requireLogin outOfService"
                    action="takeOutOfService" value="Deactivate All Applications in Eureka" title="Prevent Eureka from listing this instance for use by other applications." />
            <g:buttonSubmit class="requireLogin inService"
                    action="putInService" value="Activate All Applications in Eureka" title="Allow Eureka to list this instance for use by other applications." />
         
         
          </div>
        </g:if>
      </g:form>
    </g:if>
    <div class="dialog">
      <table>
        <tbody>
            <tr class="prop" title="Effective public hostname from Eureka or EC2">
          <td class="name">DNS Name:</td>
          <td class="value">${baseServer}</td>
        </tr>
        <g:each in="${linkGroupingsToListsOfTextLinks}" var="groupingToTextLinks">
          <tr>
            <td class="name">${groupingToTextLinks.key}:</td>
            <td class="value">
              <g:each in="${groupingToTextLinks.value}" var="textLink">
                <a href="${textLink.url}">${textLink.text}</a><br/>
              </g:each>
            </td>
          </tr>
        </g:each>
        <g:if test="${discoveryExists}">
          <tr >
            <td><h2 title="Information from Eureka">Eureka</h2></td>
          </tr>
          <g:if test="${applicationInstances}">
            <g:each var="discInstance" in="${applicationInstances}" > 
              
          <tr><td colspan="2">
              <g:form class="validate">
                 <input type="hidden" name="instanceId" value="${instance.id}"/>
                 <input type="hidden" name="providerId" value="${instance.providerId}"/>
              
            <input type="hidden" name="appNames" value="${discInstance.appName}"/>
            
            
           
            <div class="buttons">
            <g:buttonSubmit class="requireLogin outOfService"
                    action="takeOutOfService" value="Deactivate in Eureka" title="Prevent Eureka from listing this instance for use by other applications." />
           
            <g:buttonSubmit class="requireLogin inService"
                    action="putInService" value="Activate in Eureka" title="Allow Eureka to list this instance for use by other applications." />
  
             </div>
           
            </g:form>
             </td><td></td></tr>
                <tr class="prop">
              <td class="name">Applications:</td>
              <td class="value">${discInstance.appName}</td>
            </tr>
            <tr class="prop">
              <td class="name">DNS Name/IP:</td>
              <td class="value">${discInstance.hostName} | ${discInstance.ipAddr}</td>
            </tr>
            <tr class="prop">
              <td class="name">Port:</td>
              <td class="value">${discInstance.port}</td>
            </tr>
            <tr class="prop">
              <td class="name">Status Page:</td>
              <td class="value"><a href="${discInstance.statusPageUrl}">${discInstance.statusPageUrl}</a></td>
            </tr>
            <tr class="prop">
              <td class="name">Health Check:</td>
              <td class="value"><a href="${discInstance.healthCheckUrl}">${discInstance.healthCheckUrl}</a> : (${healthCheck})</td>
            </tr>
            <tr class="prop">
              <td class="name">VIP Address:</td>
              <td class="value">${discInstance.vipAddress}</td>
            </tr>
            <tr class="prop">
              <td class="name">Lease Info</td>
              <td>
                <table>
                  <g:each var="data" in="${discInstance.leaseInfo}" status="i">
                    <tr class="prop">
                      <td class="name">${data.key}:</td>
                      <td class="value">${data.value}</td>
                    </tr>
                  </g:each>
                </table>
              </td>
            </tr>
            <tr class="prop">
              <td class="name">Status:</td>
              <td class="value ${discInstance.status == "UP" ? "inService" : "outOfService"}">${discInstance.status}</td>
            </tr>
            </g:each>
          </g:if>
          <g:else>
            <tr><td>Not found in Eureka</td></tr>
          </g:else>
        </g:if>
        <tr class="prop">
          <td><h2 title="Information from AWS EC2">EC2</h2></td>
        </tr>
        <g:if test="${instance}">
          <tr class="prop">
            <td class="name">Instance ID:</td>
            <td class="value">${instance.id}</td>
          </tr><%--
          <g:if test="${instance.id}">
            <tr class="prop">
              <td class="name">Spot Instance Request:</td>
              <td class="value"><g:linkObject type="spotInstanceRequest" name="${java.net.URLEncoder.encode(instance.id,'UTF-8')}" >${instance.id}</g:linkObject></td>
            </tr>
          </g:if>
          --%><tr class="prop">
            <td class="name">Public DNS/IP:</td>
            <td class="value">${instance.publicAddresses}</td>
          </tr>
          <tr class="prop">
            <td class="name">Private DNS/IP:</td>
             <td class="value">${instance.privateAddresses}</td>
          </tr>
          <tr class="prop">
            <td class="name">Image:</td>
            <td class="value">
              <g:linkObject type="image" name="${java.net.URLEncoder.encode(instance.imageId,'UTF-8')}">${instance.imageId}</g:linkObject>${image ? ' | ' + image.operatingSystem + ' | ' + image.location : ''}
            </td>
          </tr>
          <tr class="prop">
            <td class="name"><g:link controller="instanceType" action="list">Instance Type:</g:link></td>
            <td class="value">${instance.hardware?:instance.type}</td>
          </tr>
          <tr class="prop">
            <td class="name">Zone:</td>
            <td class="value"><g:availabilityZone value="${instance.location.id}"/></td>
          </tr>
          <tr class="prop">
            <td class="name">State (Transition Reason):</td>
            <td class="value">${instance.status}</td>
          </tr></g:if></table>
      
        </tbody>
    </div>
  </div>
</body>
</html>
