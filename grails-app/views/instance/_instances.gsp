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
<div class="list">
  ${body()}
  <table class="sortable">
    <thead>
    <tr>
      <th>&thinsp;x</th>
      <th>Instance ID</th>
      <th>Image ID</th>
      <th>Inst Type</th>
      <th>Zone</th>
      <g:if test="${discoveryExists}">
        <th>Application</th>
      </g:if>
      <th><g:if test="${discoveryExists}">VIP & </g:if>Hostname</th>
      <g:if test="${discoveryExists}">
        <th>Port</th>
      </g:if>
      <th>Status</th>
      
      <th>Tags</th>
      <th>Launch Time</th>
    </tr>
    </thead>
    <tbody>
    <g:each var="mi" in="${instanceList}" status="i">
      <tr class="${(i % 2) == 0 ? 'odd' : 'even'}">
        <td><g:if test="${mi.instanceId}"><g:checkBox name="selectedInstances" value="${mi.instanceId}" checked="0" class="requireLogin"/></g:if></td>
        
         <td><g:linkObject type="instance" name="${mi.instanceId.encodeAsURL()}">${mi.instanceId}</g:linkObject></td>
        <td><g:linkObject type="image" name="${mi.amiId.encodeAsURL()}">${mi.amiId}</g:linkObject></td>
       <td>${mi.instanceType}</td>
        <td><g:availabilityZone value="${mi.zone}"/></td>
        <g:if test="${discoveryExists}">
         <td>
         <g:each var="appi" in="${mi.appInstances}" >
           <g:link class="application" controller="application" action="show" id="${appi.appName}">${appi.appName}</g:link>
          </g:each>
          </td>  
        </g:if>
        
        <td>
          <g:set var="vipHost"><g:if test="${discoveryExists}">${mi.appInstances*.vipAddress} <br/> </g:if>${mi.hostName}</g:set>
          <g:if test="${!mi.instanceId}">
            <g:link class="instance" action="show" params="[appName:mi.appName,instanceId:mi.instanceId,hostName:mi.hostName]"
                                title="Show details of this instance">${vipHost}</g:link>
          </g:if>
          <g:else>
            ${vipHost}
          </g:else>
        </td>
        <g:if test="${discoveryExists}">
        
          <td>
          <g:each var="appi" in="${mi.appInstances}" > 
          ${appi.appName} - ${appi.port}
          </g:each>
          </td>
          
         </g:if>
        <td>${mi.status}</td>
       
        <td class="variables">
          <g:if test="${mi.listTags()}">
            <g:each var="tag" in="${mi.listTags()}"><%--
              <span class="tagKey">${tag.key}:</span> ${tag.value}<br/>
            --%></g:each>
          </g:if>
        </td>
        <td><g:formatDate date="${mi.launchTime}"/></td>
      </tr>
    </g:each>
    </tbody>
  </table>
</div>
