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

import de.tu_dortmund.ub.data.util.XmlTransformer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.jdom2.Document;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.openrdf.model.*;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.Rio;

import javax.json.*;
import java.io.*;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Task for Task Processing Unit for d:swarm
 *
 * @author Dipl.-Math. Hans-Georg Becker (M.L.I.S.)
 * @version 2015-04-20
 *
 */
public class Task implements Callable<String> {

    private Properties config = null;
    private Logger logger = null;

    private String resource;
    private int cnt;

    public Task(Properties config, Logger logger, String resource, int cnt) {

        this.config = config;
        this.logger = logger;
        this.resource = resource;
        this.cnt = cnt;
    }

    @Override
    public String call() {

        // init logger
        PropertyConfigurator.configure(config.getProperty("service.log4j-conf"));

        logger.info("[" + config.getProperty("service.name") + "] " + "Starting 'Task' ...");

        // init IDs of the prototype project
        String dataModelID = config.getProperty("prototype.dataModelID");
        String projectID = config.getProperty("prototype.projectID");
        String outputDataModelID = config.getProperty("prototype.outputDataModelID"); // Internal Data Model BiboDocument

        // init process values
        String inputResourceID = null;
        String resourceID = null;
        String inputDataModelID = null;
        String message = null;

        try {
            // build a InputDataModel for the resource
            String inputResourceJson = uploadFileToDSwarm(resource, "resource for project '" + resource, config.getProperty("project.name") + "' - case " + cnt);
            JsonReader jsonReader = Json.createReader(IOUtils.toInputStream(inputResourceJson, "UTF-8"));
            inputResourceID = jsonReader.readObject().getString("uuid");
            logger.info("[" + config.getProperty("service.name") + "] inputResourceID = " + inputResourceID);

            if (inputResourceID != null) {

                // get the resource id of the resource for the data model for the the prototype project
                resourceID = getProjectResourceID(dataModelID);

                if (resourceID != null) {
                    // get the configurations of the prototype resource and build a data model for the new resource
                    inputDataModelID = configureDataModel(inputResourceJson, resourceID, "resource for project '" + resource, config.getProperty("project.name") + "' - case " + cnt);

                    if (inputDataModelID != null) {
                        // configuration and processing of the task
                        String jsonResponse = executeTask(inputDataModelID, projectID, resourceID, outputDataModelID);

                        if (jsonResponse != null) {

                            if (Boolean.parseBoolean(config.getProperty("results.persistInFolder"))) {

                                if (Boolean.parseBoolean(config.getProperty("results.writeDMPJson"))) {
                                    // save DMP results in files
                                    FileUtils.writeStringToFile(new File(config.getProperty("results.folder") + File.separatorChar + inputDataModelID + ".json"), jsonResponse);
                                }

                                // build rdf graph
                                ValueFactory factory = ValueFactoryImpl.getInstance();

                                Graph graph = new LinkedHashModel();

                                URI graphUri = factory.createURI(config.getProperty("results.rdf.graph"));

                                URI subject = null;
                                URI predicate = null;
                                URI object = null;
                                Literal literal = null;
                                Statement statement = null;

                                JsonReader dmpJsonResult = Json.createReader(IOUtils.toInputStream(jsonResponse, "UTF-8"));
                                JsonArray records = dmpJsonResult.readArray();

                                for (JsonObject record : records.getValuesAs(JsonObject.class)) {

                                    subject = factory.createURI(record.getJsonString("__record_id").toString().replaceAll("\"", ""));

                                    for (JsonObject triple : record.getJsonArray("__record_data").getValuesAs(JsonObject.class)) {

                                        for (String key : triple.keySet()) {

                                            if (key.endsWith("rdf-syntax-ns#type")) {
                                                predicate = RDF.TYPE;
                                                object = factory.createURI(triple.getJsonString(key).toString().replaceAll("\"", ""));
                                                statement = factory.createStatement(subject, predicate, object, graphUri);
                                                graph.add(statement);
                                            }
                                            else {

                                                predicate = factory.createURI(key);

                                                switch (triple.get(key).getValueType().toString()) {

                                                    case "STRING" : {

                                                        try {
                                                            object = factory.createURI(triple.getJsonString(key).toString().replaceAll("\"", ""));
                                                            statement = factory.createStatement(subject, predicate, object, graphUri);
                                                            graph.add(statement);
                                                        }
                                                        catch (Exception e) {
                                                            literal = factory.createLiteral(triple.getJsonString(key).toString().replaceAll("\"",""));
                                                            statement = factory.createStatement(subject, predicate, literal, graphUri);
                                                            graph.add(statement);
                                                        }
                                                        break;
                                                    }
                                                    case "ARRAY" : {

                                                        for (JsonString value : triple.getJsonArray(key).getValuesAs(JsonString.class)) {

                                                            try {
                                                                object = factory.createURI(value.toString().replaceAll("\"",""));
                                                                statement = factory.createStatement(subject, predicate, object, graphUri);
                                                                graph.add(statement);
                                                            }
                                                            catch (Exception e) {
                                                                literal = factory.createLiteral(value.toString().replaceAll("\"",""));
                                                                statement = factory.createStatement(subject, predicate, literal, graphUri);
                                                                graph.add(statement);
                                                            }
                                                        }
                                                        break;
                                                    }
                                                    default: {

                                                        logger.info("Unhandled ValueType: " + triple.get(key).getValueType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (graph.size() > 0) {
                                    // save rdf data as 'results.rdf.format' in 'results.folder'
                                    RDFFormat format = null;
                                    switch (config.getProperty("results.rdf.format")) {

                                        case "xml": {

                                            format = RDFFormat.RDFXML;
                                            break;
                                        }
                                        case "nquads": {

                                            format = RDFFormat.NQUADS;
                                            break;
                                        }
                                        case "jsonld": {

                                            format = RDFFormat.JSONLD;
                                            break;
                                        }
                                        case "ttl": {

                                            format = RDFFormat.TURTLE;
                                            break;
                                        }
                                        default: {

                                            format = RDFFormat.RDFXML;
                                        }
                                    }

                                    try {
                                        FileOutputStream out = new FileOutputStream(config.getProperty("results.folder") + File.separatorChar + inputDataModelID + ".rdf." + config.getProperty("results.rdf.format"));
                                        RDFWriter writer = Rio.createWriter(format, out);

                                        writer.startRDF();
                                        for (Statement st : graph) {
                                            writer.handleStatement(st);
                                        }
                                        writer.endRDF();

                                        out.close();

                                    } catch (RDFHandlerException | IOException e) {
                                        e.printStackTrace();
                                    }

                                    message = "'" + resource + "' transformed. results in '" + config.getProperty("results.folder") + File.separatorChar + inputDataModelID + ".rdf." + config.getProperty("results.rdf.format") + "'";
                                }
                                else {

                                    message = "'" + resource + "' transformed but result is empty.";
                                }
                            }
                        }
                        else {

                            message = "'" + resource + "' not transformed: error in task execution.";
                        }
                    }
                }
            }

            // cleanup data model and resource in d:swarm
            cleanup(inputResourceID, inputDataModelID);
        }
        catch (Exception e) {

            logger.error("[" + config.getProperty("service.name") + "] Processing resource '" + resource + "' failed with a " + e.getClass().getSimpleName());
            e.printStackTrace();
        }

        return message;
    }

    /**
     * cleanup data model and resource in d:swarm
     *
     * @param inputResourceID
     * @param inputDataModelID
     */
    private void cleanup(String inputResourceID, String inputDataModelID) throws Exception {

        CloseableHttpClient httpclient = HttpClients.createDefault();

        try {

            if (inputDataModelID != null) {

                HttpDelete httpDelete = new HttpDelete(config.getProperty("engine.dswarm.api") + "datamodels/" + inputDataModelID);

                CloseableHttpResponse httpResponse = httpclient.execute(httpDelete);

                logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpDelete.getRequestLine());

                try {

                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    HttpEntity httpEntity = httpResponse.getEntity();

                    switch (statusCode) {

                        case 204: {

                            logger.info("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());

                            break;
                        }
                        default: {

                            logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                        }
                    }

                    EntityUtils.consume(httpEntity);

                } finally {
                    httpResponse.close();
                }
            }

            if (inputResourceID != null) {

                HttpDelete httpDelete = new HttpDelete(config.getProperty("engine.dswarm.api") + "resources/" + inputResourceID);

                CloseableHttpResponse httpResponse = httpclient.execute(httpDelete);

                logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpDelete.getRequestLine());

                try {

                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    HttpEntity httpEntity = httpResponse.getEntity();

                    switch (statusCode) {

                        case 200: {

                            logger.info("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());

                            break;
                        }
                        default: {

                            logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                        }
                    }

                    EntityUtils.consume(httpEntity);

                } finally {
                    httpResponse.close();
                }
            }

        } finally {
            httpclient.close();
        }
    }

    /**
     * configuration and processing of the task
     *
     * @param inputDataModelID
     * @param projectID
     * @param outputDataModelID
     * @return
     */
    private String executeTask(String inputDataModelID, String projectID, String resourceID, String outputDataModelID) throws Exception {

        String jsonResponse = null;

        CloseableHttpClient httpclient = HttpClients.createDefault();

        try {

            // Hole Mappings aus dem Projekt mit 'projectID'
            HttpGet httpGet = new HttpGet(config.getProperty("engine.dswarm.api") + "projects/" + projectID);

            CloseableHttpResponse httpResponse = httpclient.execute(httpGet);

            logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpGet.getRequestLine());

            String mappings = "";

            try {

                int statusCode = httpResponse.getStatusLine().getStatusCode();
                HttpEntity httpEntity = httpResponse.getEntity();

                switch (statusCode) {

                    case 200: {

                        StringWriter writer = new StringWriter();
                        IOUtils.copy(httpEntity.getContent(), writer, "UTF-8");
                        String responseJson = writer.toString();

                        logger.info("[" + config.getProperty("service.name") + "] responseJson : " + responseJson);

                        JsonReader jsonReader = Json.createReader(IOUtils.toInputStream(responseJson, "UTF-8"));
                        JsonObject jsonObject = jsonReader.readObject();

                        mappings = jsonObject.getJsonArray("mappings").toString();

                        logger.info("[" + config.getProperty("service.name") + "] mappings : " + mappings);

                        break;
                    }
                    default: {

                        logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                    }
                }

                EntityUtils.consume(httpEntity);
            } finally {
                httpResponse.close();
            }

            // Hole InputDataModel
            String inputDataModel = "";

            httpGet = new HttpGet(config.getProperty("engine.dswarm.api") + "datamodels/" + inputDataModelID);

            httpResponse = httpclient.execute(httpGet);

            logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpGet.getRequestLine());

            try {

                int statusCode = httpResponse.getStatusLine().getStatusCode();
                HttpEntity httpEntity = httpResponse.getEntity();

                switch (statusCode) {

                    case 200: {

                        StringWriter writer = new StringWriter();
                        IOUtils.copy(httpEntity.getContent(), writer, "UTF-8");
                        inputDataModel = writer.toString();

                        logger.info("[" + config.getProperty("service.name") + "] inputDataModel : " + inputDataModel);

                        JsonReader jsonReader = Json.createReader(IOUtils.toInputStream(inputDataModel, "UTF-8"));
                        JsonObject jsonObject = jsonReader.readObject();

                        String inputResourceID = jsonObject.getJsonObject("data_resource").getString("uuid");

                        mappings = mappings.replaceAll(resourceID, inputResourceID);

                        logger.info("[" + config.getProperty("service.name") + "] mappings : " + mappings);

                        break;
                    }
                    default: {

                        logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                    }
                }

                EntityUtils.consume(httpEntity);
            } finally {
                httpResponse.close();
            }

            // Hole OutputDataModel
            String outputDataModel = "";

            httpGet = new HttpGet(config.getProperty("engine.dswarm.api") + "datamodels/" + outputDataModelID);

            httpResponse = httpclient.execute(httpGet);

            logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpGet.getRequestLine());

            try {

                int statusCode = httpResponse.getStatusLine().getStatusCode();
                HttpEntity httpEntity = httpResponse.getEntity();

                switch (statusCode) {

                    case 200: {

                        StringWriter writer = new StringWriter();
                        IOUtils.copy(httpEntity.getContent(), writer, "UTF-8");
                        outputDataModel = writer.toString();

                        logger.info("[" + config.getProperty("service.name") + "] outputDataModel : " + outputDataModel);

                        break;
                    }
                    default: {

                        logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                    }
                }

                EntityUtils.consume(httpEntity);
            } finally {
                httpResponse.close();
            }

            // erzeuge Task-JSON
            String task = "{";
            task += "\"name\":\"" + "Task Batch-Prozess 'CrossRef'" + "\",";
            task += "\"description\":\"" + "Task Batch-Prozess 'CrossRef' zum InputDataModel '" + inputDataModelID + "'\",";
            task += "\"job\": { " +
                    "\"mappings\": " + mappings + "," +
                    "\"uuid\": \"" + UUID.randomUUID() + "\"" +
                    " },";
            task += "\"input_data_model\":" + inputDataModel + ",";
            task += "\"output_data_model\":" + outputDataModel;
            task += "}";

            logger.info("[" + config.getProperty("service.name") + "] task : " + task);

            // POST /dmp/tasks/
            HttpPost httpPost = new HttpPost(config.getProperty("engine.dswarm.api") + "tasks?persist=" + config.getProperty("results.persistInDMP"));
            StringEntity stringEntity = new StringEntity(task, ContentType.create("application/json", Consts.UTF_8));
            httpPost.setEntity(stringEntity);

            logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpPost.getRequestLine());

            httpResponse = httpclient.execute(httpPost);

            try {

                int statusCode = httpResponse.getStatusLine().getStatusCode();
                HttpEntity httpEntity = httpResponse.getEntity();

                switch (statusCode) {

                    case 200: {

                        logger.info("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());

                        StringWriter writer = new StringWriter();
                        IOUtils.copy(httpEntity.getContent(), writer, "UTF-8");
                        jsonResponse = writer.toString();

                        logger.info("[" + config.getProperty("service.name") + "] jsonResponse : " + jsonResponse);

                        break;
                    }
                    default: {

                        logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                    }
                }

                EntityUtils.consume(httpEntity);
            } finally {
                httpResponse.close();
            }

        } finally {
            httpclient.close();
        }

        return jsonResponse;
    }

    /**
     * get the configurations of the prototype resource and build a data model for the new resource
     *
     * @param inputResourceJson
     * @param resourceID
     * @param name
     * @param description
     * @return
     * @throws Exception
     */
    private String configureDataModel(String inputResourceJson, String resourceID, String name, String description) throws Exception {

        String inputDataModelID = null;

        JsonReader jsonReader = Json.createReader(IOUtils.toInputStream(inputResourceJson, "UTF-8"));
        String inputResourceID = jsonReader.readObject().getString("uuid");

        CloseableHttpClient httpclient = HttpClients.createDefault();

        // Hole die Konfiguration zur Prototyp-Projekt
        try {

            HttpGet httpGet = new HttpGet(config.getProperty("engine.dswarm.api") + "resources/" + resourceID + "/configurations");

            CloseableHttpResponse httpResponse = httpclient.execute(httpGet);

            logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpGet.getRequestLine());

            String json = "";

            try {

                int statusCode = httpResponse.getStatusLine().getStatusCode();
                HttpEntity httpEntity = httpResponse.getEntity();

                switch (statusCode) {

                    case 200: {

                        StringWriter writer = new StringWriter();
                        IOUtils.copy(httpEntity.getContent(), writer, "UTF-8");
                        String responseJson = writer.toString();

                        logger.info("[" + config.getProperty("service.name") + "] responseJson : " + responseJson);

                        // replace resourceID with inputResourceID
                        responseJson = responseJson.substring(1, responseJson.length() - 1);

                        jsonReader = Json.createReader(IOUtils.toInputStream(responseJson, "UTF-8"));
                        JsonObject jsonObject = jsonReader.readObject();

                        String nameParam = "";
                        try {
                            nameParam = jsonObject.getString("name");
                        }
                        catch (Exception e) {

                            // erstmal nix weiter
                        }
                        logger.info("[" + config.getProperty("service.name") + "] nameParam : " + nameParam);
                        String descriptionParam = "";
                        try {
                            descriptionParam = jsonObject.getString("description");
                        }
                        catch (Exception e) {

                            // erstmal nix weiter
                        }
                        logger.info("[" + config.getProperty("service.name") + "] descriptionParam : " + descriptionParam);
                        String resourcesParam = "";
                        try {
                            resourcesParam = jsonObject.getJsonArray("resources").toString();
                        }
                        catch (Exception e) {

                            // erstmal nix weiter
                        }
                        logger.info("[" + config.getProperty("service.name") + "] resourcesParam : " + resourcesParam);
                        String parametersParam = "";
                        try {
                            parametersParam = jsonObject.getJsonObject("parameters").toString();
                        }
                        catch (Exception e) {

                            // erstmal nix weiter
                        }
                        logger.info("[" + config.getProperty("service.name") + "] parametersParam : " + parametersParam);

                        json = "{";
                        json += "\"uuid\":\"" + UUID.randomUUID() + "\",";
                        json += "\"name\":\"" + nameParam + "\",";
                        json += "\"description\":\"" + descriptionParam.replaceAll(resourceID,inputResourceID) + "\",";
                        json += "\"resources\":" + resourcesParam.replaceAll(resourceID,inputResourceID) + ",";
                        json += "\"parameters\":" + parametersParam;
                        json += "}";

                        logger.info("[" + config.getProperty("service.name") + "] json : " + json);

                        break;
                    }
                    default: {

                        logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                    }
                }

                EntityUtils.consume(httpEntity);
            } finally {
                httpResponse.close();
            }

            if (!json.equals("")) {

                // Konfiguration der neuen Resource: http://129.217.132.83:8080/dmp/resources/{uuid der neuen Ressource}/configurations
                HttpPost httpPost = new HttpPost(config.getProperty("engine.dswarm.api") + "resources/" + inputResourceID + "/configurations");
                StringEntity stringEntity = new StringEntity(json, ContentType.create("application/json", Consts.UTF_8));
                httpPost.setEntity(stringEntity);

                logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpPost.getRequestLine());

                httpResponse = httpclient.execute(httpPost);

                try {

                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    HttpEntity httpEntity = httpResponse.getEntity();

                    switch (statusCode) {

                        case 201: {

                            logger.info("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());

                            break;
                        }
                        default: {

                            logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                        }
                    }

                    EntityUtils.consume(httpEntity);
                } finally {
                    httpResponse.close();
                }

                // Anlegen eines neuen Data Model: http://129.217.132.83:8080/dmp/datamodels
                httpPost = new HttpPost(config.getProperty("engine.dswarm.api") + "datamodels");

                String datamodel = "{ " +
                        "\"name\" : \""+ name + "\", " +
                        "\"description\" : \"" + description + "\", " +
                        "\"configuration\" : " + json + ", " +
                        "\"data_resource\" : " + inputResourceJson.replace("\"configuration\" : []", "\"configuration\" : [" + json + "]") +
                        " }";

                logger.info("[" + config.getProperty("service.name") + "] datamodel : " + datamodel);

                stringEntity = new StringEntity(datamodel, ContentType.create("application/json", Consts.UTF_8));
                httpPost.setEntity(stringEntity);

                logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpPost.getRequestLine());

                httpResponse = httpclient.execute(httpPost);

                try {

                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    HttpEntity httpEntity = httpResponse.getEntity();

                    switch (statusCode) {

                        case 201: {

                            logger.info("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());

                            jsonReader = Json.createReader(httpEntity.getContent());
                            inputDataModelID = jsonReader.readObject().getString("uuid");

                            logger.info("[" + config.getProperty("service.name") + "] inputDataModelID = " + inputDataModelID);

                            break;
                        }
                        default: {

                            logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                        }
                    }

                    EntityUtils.consume(httpEntity);
                } finally {
                    httpResponse.close();
                }
            }
        } finally {
            httpclient.close();
        }

        return inputDataModelID;
    }

    /**
     * get the resource id of the resource for the data model for the the prototype project
     *
     * @param dataModelID
     * @return
     * @throws Exception
     */
    private String getProjectResourceID(String dataModelID) throws Exception {

        String resourceID = null;

        CloseableHttpClient httpclient = HttpClients.createDefault();

        try {

            // Hole Mappings aus dem Projekt mit 'projectID'
            HttpGet httpGet = new HttpGet(config.getProperty("engine.dswarm.api") + "datamodels/" + dataModelID);

            CloseableHttpResponse httpResponse = httpclient.execute(httpGet);

            logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpGet.getRequestLine());

            try {

                int statusCode = httpResponse.getStatusLine().getStatusCode();
                HttpEntity httpEntity = httpResponse.getEntity();

                switch (statusCode) {

                    case 200: {

                        StringWriter writer = new StringWriter();
                        IOUtils.copy(httpEntity.getContent(), writer, "UTF-8");
                        String responseJson = writer.toString();

                        logger.info("[" + config.getProperty("service.name") + "] responseJson : " + responseJson);

                        JsonReader jsonReader = Json.createReader(IOUtils.toInputStream(responseJson, "UTF-8"));
                        JsonObject jsonObject = jsonReader.readObject();
                        JsonArray resources = jsonObject.getJsonObject("configuration").getJsonArray("resources");

                        resourceID = resources.getJsonObject(0).getJsonString("uuid").getString();

                        logger.info("[" + config.getProperty("service.name") + "] resourceID : " + resourceID);

                        break;
                    }
                    default: {

                        logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                    }
                }

                EntityUtils.consume(httpEntity);
            } finally {
                httpResponse.close();
            }

        } finally {
            httpclient.close();
        }

        return resourceID;
    }

    /**
     * build a InputDataModel for the resource
     *
     * @param filename
     * @param name
     * @param description
     * @return
     * @throws Exception
     */
    private String uploadFileToDSwarm(String filename, String name, String description) throws Exception {

        String responseJson = null;

        String file = config.getProperty("resource.watchfolder") + File.separatorChar +  filename;

        // ggf. Preprocessing: insert CDATA in XML and write new XML file to tmp folder
        if (Boolean.parseBoolean(config.getProperty("resource.preprocessing"))) {

            Document document = new SAXBuilder().build(new File(file));

            file = config.getProperty("preprocessing.folder") + File.separatorChar + UUID.randomUUID() + ".xml";

            XMLOutputter out = new XMLOutputter(Format.getPrettyFormat());
            BufferedWriter bufferedWriter = null;
            try {

                bufferedWriter = new BufferedWriter(new FileWriter(file));

                out.output(new SAXBuilder().build(new StringReader(XmlTransformer.xmlOutputter(document, config.getProperty("preprocessing.xslt"), null))), bufferedWriter);
            }
            finally {
                if (bufferedWriter != null) {
                    bufferedWriter.close();
                }
            }
        }

        // upload
        CloseableHttpClient httpclient = HttpClients.createDefault();

        try {
            HttpPost httpPost = new HttpPost(config.getProperty("engine.dswarm.api") + "resources/");

            FileBody fileBody = new FileBody(new File(file));
            StringBody stringBodyForName = new StringBody(name, ContentType.TEXT_PLAIN);
            StringBody stringBodyForDescription = new StringBody(description, ContentType.TEXT_PLAIN);

            HttpEntity reqEntity = MultipartEntityBuilder.create()
                    .addPart("file", fileBody)
                    .addPart("name", stringBodyForName)
                    .addPart("description", stringBodyForDescription)
                    .build();

            httpPost.setEntity(reqEntity);

            logger.info("[" + config.getProperty("service.name") + "] " + "request : " + httpPost.getRequestLine());

            CloseableHttpResponse httpResponse = httpclient.execute(httpPost);

            try {
                int statusCode = httpResponse.getStatusLine().getStatusCode();
                HttpEntity httpEntity = httpResponse.getEntity();

                switch (statusCode) {

                    case 201: {

                        logger.info("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                        StringWriter writer = new StringWriter();
                        IOUtils.copy(httpEntity.getContent(), writer, "UTF-8");
                        responseJson = writer.toString();

                        logger.info("[" + config.getProperty("service.name") + "] responseJson : " + responseJson);

                        break;
                    }
                    default: {

                        logger.error("[" + config.getProperty("service.name") + "] " + statusCode + " : " + httpResponse.getStatusLine().getReasonPhrase());
                    }
                }

                EntityUtils.consume(httpEntity);
            } finally {
                httpResponse.close();
            }
        } finally {
            httpclient.close();
        }

        // ggf. Löschen des tmp resource file
        if (Boolean.parseBoolean(config.getProperty("resource.preprocessing"))) {

            File f = new File(file);

            boolean isDeleted = f.delete();

            logger.info("[" + config.getProperty("service.name") + "] tmp file '" + file + "' deleted? " + isDeleted);
        }

        return responseJson;
    }
}
