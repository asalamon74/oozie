/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.servlet;

import java.io.IOException;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.hadoop.conf.Configuration;
import org.apache.oozie.BaseEngineException;
import org.apache.oozie.ErrorCode;
import org.apache.oozie.client.OozieClient;
import org.apache.oozie.client.XOozieClient;
import org.apache.oozie.client.rest.JsonBean;
import org.apache.oozie.client.rest.JsonTags;
import org.apache.oozie.client.rest.RestConstants;
import org.apache.oozie.service.AuthorizationException;
import org.apache.oozie.service.AuthorizationService;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.XLogService;
import org.apache.oozie.util.ConfigUtils;
import org.apache.oozie.util.JobUtils;
import org.apache.oozie.util.XConfiguration;
import org.apache.oozie.util.XLog;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public abstract class BaseJobServlet extends JsonRestServlet {

    private static final ResourceInfo RESOURCES_INFO[] = new ResourceInfo[1];

    final static String NOT_SUPPORTED_MESSAGE = "Not supported in this version";

    static {
        RESOURCES_INFO[0] = new ResourceInfo("*", Arrays.asList("PUT", "GET"), Arrays.asList(new ParameterInfo(
                RestConstants.ACTION_PARAM, String.class, true, Arrays.asList("PUT")), new ParameterInfo(
                RestConstants.JOB_SHOW_PARAM, String.class, false, Arrays.asList("GET")), new ParameterInfo(
                        RestConstants.ORDER_PARAM, String.class, false, Arrays.asList("GET"))));
    }

    public BaseJobServlet(String instrumentationName) {
        super(instrumentationName, RESOURCES_INFO);
    }

    /**
     * Perform various job related actions - start, suspend, resume, kill, etc.
     */
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String jobId = getResourceName(request);
        request.setAttribute(AUDIT_PARAM, jobId);
        request.setAttribute(AUDIT_OPERATION, request.getParameter(RestConstants.ACTION_PARAM));
        try {
            AuthorizationService auth = Services.get().get(AuthorizationService.class);
            auth.authorizeForJob(getUser(request), jobId, true);
        }
        catch (AuthorizationException ex) {
            throw new XServletException(HttpServletResponse.SC_UNAUTHORIZED, ex);
        }

        String action = request.getParameter(RestConstants.ACTION_PARAM);
        if (action.equals(RestConstants.JOB_ACTION_START)) {
            stopCron();
            startJob(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else if (action.equals(RestConstants.JOB_ACTION_RESUME)) {
            stopCron();
            resumeJob(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else if (action.equals(RestConstants.JOB_ACTION_SUSPEND)) {
            stopCron();
            suspendJob(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else if (action.equals(RestConstants.JOB_ACTION_KILL)) {
            stopCron();
            JSONObject json =  killJob(request, response);
            startCron();
            if (json != null) {
                sendJsonResponse(response, HttpServletResponse.SC_OK, json);
            }
            else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
        }
        else if (action.equals(RestConstants.JOB_ACTION_CHANGE)) {
            stopCron();
            changeJob(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else if (action.equals(RestConstants.JOB_ACTION_IGNORE)) {
            stopCron();
            JSONObject json = ignoreJob(request, response);
            startCron();
            if (json != null) {
                sendJsonResponse(response, HttpServletResponse.SC_OK, json);
            }
            else {
            response.setStatus(HttpServletResponse.SC_OK);
            }
        }
        else if (action.equals(RestConstants.JOB_ACTION_RERUN)) {
            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
            Configuration conf = new XConfiguration(request.getInputStream());
            stopCron();
            String requestUser = getUser(request);
            if (!requestUser.equals(UNDEF)) {
                conf.set(OozieClient.USER_NAME, requestUser);
            }
            if (conf.get(OozieClient.APP_PATH) != null) {
                BaseJobServlet.checkAuthorizationForApp(conf);
                JobUtils.normalizeAppPath(conf.get(OozieClient.USER_NAME), conf.get(OozieClient.GROUP_NAME), conf);
            }
            reRunJob(request, response, conf);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else if (action.equals(RestConstants.JOB_COORD_ACTION_RERUN)) {
            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
            stopCron();
            JSONObject json = reRunJob(request, response, null);
            startCron();
            if (json != null) {
                sendJsonResponse(response, HttpServletResponse.SC_OK, json);
            }
            else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
        }
        else if (action.equals(RestConstants.JOB_BUNDLE_ACTION_RERUN)) {
            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
            stopCron();
            JSONObject json = reRunJob(request, response, null);
            startCron();
            if (json != null) {
                sendJsonResponse(response, HttpServletResponse.SC_OK, json);
            }
            else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
        }
        else if (action.equals(RestConstants.JOB_COORD_UPDATE)) {
            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
            Configuration conf = new XConfiguration(request.getInputStream());
            stopCron();
            String requestUser = getUser(request);
            if (!requestUser.equals(UNDEF)) {
                conf.set(OozieClient.USER_NAME, requestUser);
            }
            if (conf.get(OozieClient.COORDINATOR_APP_PATH) != null) {
                //If coord is submitted from bundle, user may want to update individual coord job with bundle properties
                //If COORDINATOR_APP_PATH is set, we should check only COORDINATOR_APP_PATH path permission
                String bundlePath = conf.get(OozieClient.BUNDLE_APP_PATH);
                if (bundlePath != null) {
                    conf.unset(OozieClient.BUNDLE_APP_PATH);
                }
                BaseJobServlet.checkAuthorizationForApp(conf);
                JobUtils.normalizeAppPath(conf.get(OozieClient.USER_NAME), conf.get(OozieClient.GROUP_NAME), conf);
                if (bundlePath != null) {
                    conf.set(OozieClient.BUNDLE_APP_PATH, bundlePath);
                }
            }
            JSONObject json = updateJob(request, response, conf);
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, json);
        }
        else if (action.equals(RestConstants.SLA_ENABLE_ALERT)) {
            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
            stopCron();
            slaEnableAlert(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else if (action.equals(RestConstants.SLA_DISABLE_ALERT)) {
            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
            stopCron();
            slaDisableAlert(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else if (action.equals(RestConstants.SLA_CHANGE)) {
            validateContentType(request, RestConstants.XML_CONTENT_TYPE);
            stopCron();
            slaChange(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
        }
        else {
            throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0303,
                    RestConstants.ACTION_PARAM, action);
        }
    }

    abstract JSONObject ignoreJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * Validate the configuration user/group. <p>
     *
     * @param conf configuration.
     * @throws XServletException thrown if the configuration does not have a property {@link
     * org.apache.oozie.client.OozieClient#USER_NAME}.
     */
    static void checkAuthorizationForApp(Configuration conf) throws XServletException {
        String user = conf.get(OozieClient.USER_NAME);
        String acl = ConfigUtils.getWithDeprecatedCheck(conf, OozieClient.GROUP_NAME, OozieClient.JOB_ACL, null);
        try {
            if (user == null) {
                throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0401, OozieClient.USER_NAME);
            }
            AuthorizationService auth = Services.get().get(AuthorizationService.class);

            if (acl != null){
            	 conf.set(OozieClient.GROUP_NAME, acl);
            }
            else if (acl == null && auth.useDefaultGroupAsAcl()) {
                acl = auth.getDefaultGroup(user);
                conf.set(OozieClient.GROUP_NAME, acl);
            }
            XLog.Info.get().setParameter(XLogService.GROUP, acl);
            String wfPath = conf.get(OozieClient.APP_PATH);
            String coordPath = conf.get(OozieClient.COORDINATOR_APP_PATH);
            String bundlePath = conf.get(OozieClient.BUNDLE_APP_PATH);

            if (wfPath == null && coordPath == null && bundlePath == null) {
                String[] libPaths = conf.getStrings(XOozieClient.LIBPATH);
                if (libPaths != null && libPaths.length > 0 && libPaths[0].trim().length() > 0) {
                    conf.set(OozieClient.APP_PATH, libPaths[0].trim());
                    wfPath = libPaths[0].trim();
                }
                else {
                    throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0405);
                }
            }

            ServletUtilities.validateAppPath(wfPath, coordPath, bundlePath);

            if (wfPath != null) {
                auth.authorizeForApp(user, acl, wfPath, "workflow.xml", conf);
            }
            else if (coordPath != null){
                auth.authorizeForApp(user, acl, coordPath, "coordinator.xml", conf);
            }
            else if (bundlePath != null){
                auth.authorizeForApp(user, acl, bundlePath, "bundle.xml", conf);
            }
        }
        catch (AuthorizationException ex) {
            XLog.getLog(BaseJobServlet.class).info("AuthorizationException ", ex);
            throw new XServletException(HttpServletResponse.SC_UNAUTHORIZED, ex);
        }
    }

    /**
     * Return information about jobs.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String jobId = getResourceName(request);
        String show = request.getParameter(RestConstants.JOB_SHOW_PARAM);
        String timeZoneId = request.getParameter(RestConstants.TIME_ZONE_PARAM) == null
                ? "GMT" : request.getParameter(RestConstants.TIME_ZONE_PARAM);

        try {
            AuthorizationService auth = Services.get().get(AuthorizationService.class);
            auth.authorizeForJob(getUser(request), jobId, false);
        }
        catch (AuthorizationException ex) {
            throw new XServletException(HttpServletResponse.SC_UNAUTHORIZED, ex);
        }

        if (show == null || show.equals(RestConstants.JOB_SHOW_INFO)) {
            stopCron();
            JsonBean job = null;
            try {
                job = getJob(request, response);
            }
            catch (BaseEngineException e) {
                // TODO Auto-generated catch block
                // e.printStackTrace();

                throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, e);
            }
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, job, timeZoneId);
        }
        else if (show.equals(RestConstants.ALL_WORKFLOWS_FOR_COORD_ACTION)) {
            stopCron();
            JSONObject json = getJobsByParentId(request, response);
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, json);
        }
        else if (show.equals(RestConstants.JOB_SHOW_JMS_TOPIC)) {
            stopCron();
            String jmsTopicName = getJMSTopicName(request, response);
            JSONObject json = new JSONObject();
            json.put(JsonTags.JMS_TOPIC_NAME, jmsTopicName);
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, json);
        }

        else if (show.equals(RestConstants.JOB_SHOW_LOG)) {
            response.setContentType(TEXT_UTF8);
            streamJobLog(request, response);
        }
        else if (show.equals(RestConstants.JOB_SHOW_ERROR_LOG)) {
            response.setContentType(TEXT_UTF8);
            streamJobErrorLog(request, response);
        }
        else if (show.equals(RestConstants.JOB_SHOW_AUDIT_LOG)) {
            response.setContentType(TEXT_UTF8);
            streamJobAuditLog(request, response);
        }

        else if (show.equals(RestConstants.JOB_SHOW_DEFINITION)) {
            stopCron();
            response.setContentType(XML_UTF8);
            String wfDefinition = getJobDefinition(request, response);
            startCron();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(wfDefinition);
        }
        else if (show.equals(RestConstants.JOB_SHOW_GRAPH)) {
            stopCron();
            streamJobGraph(request, response);
            startCron(); // -- should happen before you stream anything in response?
        } else if (show.equals(RestConstants.JOB_SHOW_STATUS)) {
            stopCron();
            String status = getJobStatus(request, response);
            JSONObject json = new JSONObject();
            json.put(JsonTags.STATUS, status);
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, json);
        } else if (show.equals(RestConstants.JOB_SHOW_ACTION_RETRIES_PARAM)) {
            stopCron();
            JSONArray retries = getActionRetries(request, response);
            JSONObject json = new JSONObject();
            json.put(JsonTags.WORKFLOW_ACTION_RETRIES, retries);
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, json);
        }
        else if (show.equals(RestConstants.COORD_ACTION_MISSING_DEPENDENCIES)) {
            stopCron();
            JSONObject json = getCoordActionMissingDependencies(request, response);
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, json);
        }
        else if (show.equals(RestConstants.JOB_SHOW_WF_ACTIONS_IN_COORD)) {
            stopCron();
            JSONObject json = getWfActionByJobIdAndName(request, response);
            startCron();
            sendJsonResponse(response, HttpServletResponse.SC_OK, json);
        }
        else {
            throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0303,
                    RestConstants.JOB_SHOW_PARAM, show);
        }
    }

    /**
     * abstract method to start a job, either workflow or coordinator
     *
     * @param request the request
     * @param response the response
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract void startJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to resume a job, either workflow or coordinator
     *
     * @param request the request
     * @param response the response
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract void resumeJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to suspend a job, either workflow or coordinator
     *
     * @param request the request
     * @param response the response
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract void suspendJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to kill a job, either workflow or coordinator
     *
     * @param request the request
     * @param response the response
     * @return a json object about the killed job, depends on implementation
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract JSONObject killJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to change a coordinator job
     *
     * @param request the request
     * @param response the response
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
    */
    abstract void changeJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to re-run a job, either workflow or coordinator
     *
     * @param request the request
     * @param response the response
     * @param conf the configuration to use
     * @return a json object about the rerun job, depends on implementation
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract JSONObject reRunJob(HttpServletRequest request, HttpServletResponse response, Configuration conf)
            throws XServletException, IOException;

    /**
     * abstract method to get a job, either workflow or coordinator, in JsonBean representation
     * @param request the request
     * @param response the response
     * @return JsonBean representation of a job, either workflow or coordinator
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     * @throws BaseEngineException thrown if the job could not be retrieved
     */
    abstract JsonBean getJob(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException, BaseEngineException;

    /**
     * abstract method to get definition of a job, either workflow or coordinator
     *
     * @param request the request
     * @param response the response
     * @return job, either workflow or coordinator, definition in string format
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract String getJobDefinition(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * abstract method to get and stream log information of job, either workflow or coordinator
     *
     * @param request the request
     * @param response the response
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract void streamJobLog(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to get and stream error log information of job, either workflow, coordinator or bundle
     *
     * @param request the request
     * @param response the response
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract void streamJobErrorLog(HttpServletRequest request, HttpServletResponse response) throws XServletException,
    IOException;


    abstract void streamJobAuditLog(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * abstract method to create and stream image for runtime DAG -- workflow only
     *
     * @param request the request
     * @param response the response
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract void streamJobGraph(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * abstract method to get JMS topic name for a job
     * @param request the request
     * @param response the response
     * @return the name of the JMS topic
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract String getJMSTopicName(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * abstract method to get workflow job ids from the parent id
     * i.e. coordinator action
     * @param request the request
     * @param response the response
     * @return comma-separated list of workflow job ids
     * @throws XServletException in case of any servlet error
     * @throws IOException in case of any I/O error
     */
    abstract JSONObject getJobsByParentId(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * Abstract method to Update coord job.
     *
     * @param request the request
     * @param response the response
     * @param conf the Configuration
     * @return the JSON object
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    abstract JSONObject updateJob(HttpServletRequest request, HttpServletResponse response, Configuration conf)
            throws XServletException, IOException;

    /**
     * Abstract method to get status for a job
     *
     * @param request the request
     * @param response the response
     * @return the JSON object
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    abstract String getJobStatus(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * Abstract method to enable SLA alert.
     *
     * @param request the request
     * @param response the response
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    abstract void slaEnableAlert(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * Abstract method to disable SLA alert.
     *
     * @param request the request
     * @param response the response
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    abstract void slaDisableAlert(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * Abstract method to change SLA definition.
     *
     * @param request the request
     * @param response the response
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    abstract void slaChange(HttpServletRequest request, HttpServletResponse response) throws XServletException,
            IOException;

    /**
     * Gets the action retries.
     *
     * @param request the request
     * @param response the response
     * @return the action retries
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    abstract JSONArray getActionRetries(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * Abstract method to get the coord action missing dependencies.
     *
     * @param request the request
     * @param response the response
     * @return the coord input dependencies
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    abstract JSONObject getCoordActionMissingDependencies(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException;

    /**
     * get wf actions by name in coordinator job
     *
     * @param request the request
     * @param response the response
     * @return JSONObject the JSON object
     * @throws XServletException the x servlet exception
     * @throws IOException Signals that an I/O exception has occurred.
     */
    protected JSONObject getWfActionByJobIdAndName(HttpServletRequest request, HttpServletResponse response)
            throws XServletException, IOException {
        throw new XServletException(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.E0302, NOT_SUPPORTED_MESSAGE);
    }
}
