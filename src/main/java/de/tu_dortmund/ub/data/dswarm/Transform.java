/*
The MIT License (MIT)

Copyright (c) 2015, Hans-Georg Becker, http://orcid.org/0000-0003-0432-294X

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package de.tu_dortmund.ub.data.dswarm;

import java.io.InputStream;
import java.io.StringWriter;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.stream.JsonGenerator;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

/**
 * Export Task for Task Processing Unit for d:swarm
 *
 * @author Jan Polowinski (SLUB Dresden)
 * @version 2015-04-20
 *
 */
public class Transform implements Callable<String> {

	private final Properties config;
	private final Logger     logger;
	private final String     inputDataModelID;
	private final String     outputDataModelID;
	private final String     projectID;

	public Transform(final Properties config, final String inputDataModelID, final String outputDataModelID, final Logger logger) {

		this.config = config;
		this.logger = logger;

		// init IDs of the prototype project

		if (inputDataModelID != null && !inputDataModelID.trim().isEmpty()) {

			this.inputDataModelID = inputDataModelID;
		} else {

			this.inputDataModelID = config.getProperty(TPUStatics.PROTOTYPE_INPUT_DATA_MODEL_ID_IDENTIFIER);
		}

		this.projectID = config.getProperty(TPUStatics.PROTOTYPE_PROJECT_ID_INDENTIFIER);
		this.outputDataModelID = outputDataModelID;
	}

	//    @Override
	public String call() {

		// init logger
		PropertyConfigurator.configure(config.getProperty(TPUStatics.SERVICE_LOG4J_CONF_IDENTIFIER));

		final String serviceName = config.getProperty(TPUStatics.SERVICE_NAME_IDENTIFIER);
		final String engineDswarmAPI = config.getProperty(TPUStatics.ENGINE_DSWARM_API_IDENTIFIER);

		logger.info(String.format("[%s] Starting 'Transform (Task)' ...", serviceName));

		try {

			// export and save to results folder
			final String response = executeTask(inputDataModelID, projectID, outputDataModelID, serviceName, engineDswarmAPI);
			System.out.println(response);
			logger.debug(String.format("task execution result = '%s'", response));

			return response;
		} catch (final Exception e) {

			logger.error(String.format("[%s] Transforming datamodel '%s' to '%s' failed with a " + e.getClass().getSimpleName(), serviceName,
					inputDataModelID, outputDataModelID), e);
			e.printStackTrace();
		}

		return null;
	}

	/**
	 * configuration and processing of the task
	 *
	 * @param inputDataModelID
	 * @param projectID
	 * @param outputDataModelID
	 * @return
	 */
	private String executeTask(final String inputDataModelID, final String projectID, final String outputDataModelID, final String serviceName,
			final String engineDswarmAPI) throws Exception {

		final JsonArray mappings = getMappingsFromProject(projectID, serviceName, engineDswarmAPI);
		final JsonObject inputDataModel = getDataModel(inputDataModelID, serviceName, engineDswarmAPI);
		final JsonObject outputDataModel = getDataModel(outputDataModelID, serviceName, engineDswarmAPI);

		// erzeuge Task-JSON
		final String persistString = config.getProperty(TPUStatics.PERSIST_IN_DMP_IDENTIFIER);

		final boolean persist;

		if (persistString != null && !persistString.trim().isEmpty()) {

			persist = Boolean.valueOf(persistString);
		} else {

			persist = true;
		}

		final StringWriter stringWriter = new StringWriter();
		final JsonGenerator jp = Json.createGenerator(stringWriter);

		jp.writeStartObject();
		jp.write(DswarmBackendStatics.PERSIST_IDENTIFIER, persist);
		// default for now: true, i.e., no content will be returned
		jp.write(DswarmBackendStatics.DO_NOT_RETURN_DATA_IDENTIFIER, true);

		// task
		jp.writeStartObject(DswarmBackendStatics.TASK_IDENTIFIER);
		jp.write(DswarmBackendStatics.NAME_IDENTIFIER, "Task Batch-Prozess 'CrossRef'");
		jp.write(DswarmBackendStatics.DESCRIPTION_IDENTIFIER, "Task Batch-Prozess 'CrossRef' zum InputDataModel 'inputDataModelID '");

		// job
		jp.writeStartObject(DswarmBackendStatics.JOB_IDENTIFIER);
		jp.write(DswarmBackendStatics.UUID_IDENTIFIER, UUID.randomUUID().toString());
		jp.write(DswarmBackendStatics.MAPPINGS_IDENTIFIER, mappings);
		jp.writeEnd();

		jp.write(DswarmBackendStatics.INPUT_DATA_MODEL_IDENTIFIER, inputDataModel);
		jp.write(DswarmBackendStatics.OUTPUT_DATA_MODEL_IDENTIFIER, outputDataModel);

		// end task
		jp.writeEnd();

		// end request
		jp.writeEnd();

		jp.flush();
		jp.close();

		final String task = stringWriter.toString();
		stringWriter.flush();
		stringWriter.close();

		logger.info(String.format("[%s] task : %s", serviceName, task));

		try (CloseableHttpClient httpclient = HttpClients.createDefault()) {

			// POST /dmp/tasks/
			final HttpPost httpPost = new HttpPost(engineDswarmAPI + DswarmBackendStatics.TASKS_ENDPOINT);
			final StringEntity stringEntity = new StringEntity(task, ContentType.APPLICATION_JSON);
			httpPost.setHeader(HttpHeaders.ACCEPT, APIStatics.APPLICATION_JSON_MIMETYPE);
			httpPost.setEntity(stringEntity);

			logger.info(String.format("[%s] " + "request : %s", serviceName, httpPost.getRequestLine()));

			try (CloseableHttpResponse httpResponse = httpclient.execute(httpPost)) {

				final int statusCode = httpResponse.getStatusLine().getStatusCode();
				final HttpEntity httpEntity = httpResponse.getEntity();

				switch (statusCode) {

					case 204: {

						logger.info(String.format("[%s] %d : %s", serviceName, statusCode, httpResponse.getStatusLine().getReasonPhrase()));

						return "success";
					}
					default: {

						logger.info(String.format("[%s] %d : %s", serviceName, statusCode, httpResponse.getStatusLine().getReasonPhrase()));
					}
				}

				EntityUtils.consume(httpEntity);
			}
		}

		return null;
	}

	private JsonArray getMappingsFromProject(final String projectID, final String serviceName, final String engineDswarmAPI) throws Exception {

		try (CloseableHttpClient httpclient = HttpClients.createDefault()) {

			// Hole Mappings aus dem Projekt mit 'projectID'
			final String uri = engineDswarmAPI + DswarmBackendStatics.PROJECTS_ENDPOINT + APIStatics.SLASH + projectID;
			final HttpGet httpGet = new HttpGet(uri);

			logger.info(String.format("[%s] request : %s", serviceName, httpGet.getRequestLine()));

			try (CloseableHttpResponse httpResponse = httpclient.execute(httpGet)) {

				final int statusCode = httpResponse.getStatusLine().getStatusCode();
				final HttpEntity httpEntity = httpResponse.getEntity();

				switch (statusCode) {

					case 200: {

						final StringWriter writer = new StringWriter();
						IOUtils.copy(httpEntity.getContent(), writer, APIStatics.UTF_8);
						final String responseJson = writer.toString();
						writer.flush();
						writer.close();

						logger.info(String.format("[%s] responseJson : %s", serviceName, responseJson));

						final JsonReader jsonReader = Json.createReader(IOUtils.toInputStream(responseJson, APIStatics.UTF_8));
						final JsonObject jsonObject = jsonReader.readObject();

						final JsonArray mappings = jsonObject.getJsonArray(DswarmBackendStatics.MAPPINGS_IDENTIFIER);

						logger.info(String.format("[%s] mappings : %s", serviceName, mappings.toString()));

						return mappings;
					}
					default: {

						logger.error(String.format("[%s] %d : %s", serviceName, statusCode, httpResponse.getStatusLine()
								.getReasonPhrase()));
					}
				}

				EntityUtils.consume(httpEntity);
			}
		}

		return null;
	}

	private JsonObject getDataModel(final String dataModelID, final String serviceName, final String engineDswarmAPI) throws Exception {

		try (final CloseableHttpClient httpclient = HttpClients.createDefault()) {

			// Hole Mappings aus dem Projekt mit 'projectID'
			final String uri = engineDswarmAPI + DswarmBackendStatics.DATAMODELS_ENDPOINT + APIStatics.SLASH + dataModelID;
			final HttpGet httpGet = new HttpGet(uri);

			logger.info(String.format("[%s] request : %s", serviceName, httpGet.getRequestLine()));

			try (CloseableHttpResponse httpResponse = httpclient.execute(httpGet)) {

				final int statusCode = httpResponse.getStatusLine().getStatusCode();
				final HttpEntity httpEntity = httpResponse.getEntity();

				switch (statusCode) {

					case 200: {

						final InputStream content = httpEntity.getContent();

						final JsonReader jsonReader = Json.createReader(content);
						final JsonObject jsonObject = jsonReader.readObject();

						logger.info(String.format("[%s] inputDataModel : %s", serviceName, jsonObject.toString()));

						final JsonObject dataResourceJSON = jsonObject.getJsonObject(DswarmBackendStatics.DATA_RESOURCE_IDENTIFIER);

						if (dataResourceJSON != null) {

							final String inputResourceID = dataResourceJSON.getString(DswarmBackendStatics.UUID_IDENTIFIER);

							logger.info(String.format("[%s] inout resource ID : %s", serviceName, inputResourceID));
						}

						return jsonObject;
					}
					default: {

						logger.error(String.format("[%s] %d : %s", serviceName, statusCode, httpResponse.getStatusLine()
								.getReasonPhrase()));
					}
				}

				EntityUtils.consume(httpEntity);
			}
		}

		return null;
	}
}