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
  <title>Applications</title>
</head>
<body>
<div class="body">
  <h1>Registered Applications${terms ? ' for ' + terms : ''}</h1>
  <g:if test="${flash.message}">
    <div class="message">${flash.message}</div>
  </g:if>
  <g:form method="post">
    <div class="list">
      <div class="buttons">
        <g:link class="create" action="create">Create New Application</g:link>
      </div>
      <table class="sortable">
        <thead>
        <tr>
          <th>Name</th>
          <th>Instances</th>
          <th>Description</th>
          <th>Email</th>
          <th>Owner</th>
          <th>Create Time</th>
          <th>Update Time</th>
        </tr>
        </thead>
        <tbody>
        <g:each var="app" in="${applications}" status="i">
          <tr class="${(i % 2) == 0 ? 'odd' : 'even'}">
            <td class="app"><g:link class="application" controller="application" action="show" id="${app.key}"/>${app.key}</td>
            <td><g:link class="instance" controller="instance" action="list" id="${app.key}">${instanceCountsPerAppName.getCount(app.key)}</g:link></td>
            <td class="description">${app.value.description}</td>
            <td class="email">${app.value.email}</td>
            <td class="owner">${app.value.owner}</td>
            <td><g:formatDate date="${app.value.createTime}"/></td>
            <td><g:formatDate date="${app.value.updateTime}"/></td>
          </tr>
        </g:each>
        </tbody>
      </table>
    </div>
    <div class="paginateButtons">
    </div>
  </g:form>
</div>
</body>
</html>
