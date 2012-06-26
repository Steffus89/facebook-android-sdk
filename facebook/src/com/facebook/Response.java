/**
 * Copyright 2012 Facebook
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class Response {
    private final HttpURLConnection connection;
    private final GraphObject graphObject;
    private final Exception error;

    private static final String[] ERROR_KEYS = new String[] { "error", "error_code", "error_msg", "error_reason", };
    private static final String CODE_KEY = "code";
    private static final String BODY_KEY = "body";

    private Response(HttpURLConnection connection, GraphObject graphObject, Exception error) {
        this.connection = connection;
        this.graphObject = graphObject;
        this.error = error;
    }

    public final Exception getError() {
        return this.error;
    }

    // TODO port: what about arrays of GraphObject?
    public final GraphObject getGraphObject() {
        return this.graphObject;
    }

    public final <T extends GraphObject> T getGraphObjectAs(Class<T> graphObjectClass) {
        if (this.graphObject == null) {
            return null;
        }
        return this.graphObject.cast(graphObjectClass);
    }

    public final HttpURLConnection getConnection() {
        return this.connection;
    }

    @Override
    public String toString() {
        String responseCode;
        try {
            responseCode = String.format("%d", this.connection.getResponseCode());
        } catch (IOException e) {
            responseCode = "unknown";
        }

        return new StringBuilder().append("{Response: ").append(" responseCode: ").append(responseCode)
                .append(", graphObject: ").append(this.graphObject).append(", error: ").append(this.error).toString();
    }

    static List<Response> fromHttpConnection(RequestContext context, HttpURLConnection connection,
            List<Request> requests) {
        try {
            String responseString = readHttpResponseToString(connection);

            Object resultObject = null;
            JSONTokener tokener = new JSONTokener(responseString);
            try {
                resultObject = tokener.nextValue();
            } catch (JSONException exception) {
                // TODO port: handle 'true' and 'false' by turning into dictionary; other failures are more fatal.
                throw exception;
            }

            // TODO port: skip connection-related errors in cache case

            List<Response> responses = createResponsesFromObject(connection, requests, resultObject);

            return responses;
        } catch (FacebookException facebookException) {
            return constructErrorResponses(connection, requests.size(), facebookException);
        } catch (JSONException exception) {
            // TODO specific exception type here
            FacebookException facebookException = new FacebookException(exception);
            return constructErrorResponses(connection, requests.size(), facebookException);
        } catch (IOException exception) {
            // TODO specific exception type here
            FacebookException facebookException = new FacebookException(exception);
            return constructErrorResponses(connection, requests.size(), facebookException);
        }
    }

    private static String readHttpResponseToString(HttpURLConnection connection) throws IOException, JSONException,
            FacebookServiceErrorException {
        String responseString = null;
        InputStream responseStream = null;
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode >= 400) {
                responseStream = connection.getErrorStream();
                responseString = readHttpResponseStreamToString(responseStream);

                // Make it look like an object we'd get back from a batch request so we can use the same logic
                // to handle it
                JSONObject jsonObject = new JSONObject();
                jsonObject.put(CODE_KEY, responseCode);
                jsonObject.put(BODY_KEY, responseString);

                // Wrap it in a FacebookServiceErrorException and return it
                FacebookServiceErrorException exception = checkResponseAndCreateException(jsonObject);
                throw exception;
            } else {
                responseStream = connection.getInputStream();
                responseString = readHttpResponseStreamToString(responseStream);
            }
        } finally {
            Utility.closeQuietly(responseStream);
        }
        return responseString;
    }

    private static String readHttpResponseStreamToString(InputStream inputStream) throws IOException {
        BufferedInputStream bufferedInputStream = null;
        InputStreamReader reader = null;
        try {
            bufferedInputStream = new BufferedInputStream(inputStream);
            reader = new InputStreamReader(bufferedInputStream);
            StringBuilder stringBuilder = new StringBuilder();

            final int bufferSize = 1024 * 2;
            char[] buffer = new char[bufferSize];
            int n = 0;
            while ((n = reader.read(buffer)) != -1) {
                stringBuilder.append(buffer, 0, n);
            }

            return stringBuilder.toString();
        } finally {
            Utility.closeQuietly(bufferedInputStream);
            Utility.closeQuietly(reader);
        }
    }

    private static FacebookServiceErrorException checkResponseAndCreateException(JSONObject jsonObject) {
        try {
            if (jsonObject.has(CODE_KEY)) {
                int responseCode = jsonObject.getInt(CODE_KEY);
                if (responseCode < 200 || responseCode >= 300) {
                    // TODO port: extract body
                    return new FacebookServiceErrorException(responseCode);
                }
            }
        } catch (JSONException e) {
        }

        return null;
    }

    private static List<Response> createResponsesFromObject(HttpURLConnection connection, List<Request> requests,
            Object object) throws FacebookException, JSONException {
        int numRequests = requests.size();
        List<Response> responses = new ArrayList<Response>(numRequests);
        if (numRequests == 1) {
            try {
                // Single request case -- the entire response is the result, wrap it as "body" so we can handle it
                // the same as we do in the batched case.
                JSONObject jsonObject = new JSONObject();
                jsonObject.put(BODY_KEY, object);

                responses.add(createResponseFromObject(connection, jsonObject));
            } catch (JSONException e) {
                responses.add(new Response(connection, null, new FacebookException(e)));
            } catch (FacebookException e) {
                responses.add(new Response(connection, null, e));
            }
        } else {
            if (!(object instanceof JSONArray) || ((JSONArray) object).length() != numRequests) {
                FacebookException exception = new FacebookException("TODO unexpected number of results");
                throw exception;
            }

            JSONArray jsonArray = (JSONArray) object;
            for (int i = 0; i < jsonArray.length(); ++i) {
                try {
                    Object obj = jsonArray.get(i);
                    responses.add(createResponseFromObject(connection, obj));
                } catch (JSONException e) {
                    responses.add(new Response(connection, null, new FacebookException(e)));
                } catch (FacebookException e) {
                    responses.add(new Response(connection, null, e));
                }
            }
        }
        return responses;
    }

    private static Response createResponseFromObject(HttpURLConnection connection, Object object) throws JSONException {
        // TODO port: handle getting a JSONArray here
        if (object instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) object;

            // Does this response represent an error from the service?
            for (String errorKey : ERROR_KEYS) {
                if (jsonObject.has(errorKey)) {
                    // TODO port: figure out what to put in the exception
                    Exception exception = new FacebookServiceErrorException(200);
                    return new Response(connection, null, exception);
                }
            }

            FacebookException exception = checkResponseAndCreateException(jsonObject);
            if (exception != null) {
                throw exception;
            }

            if (jsonObject.has(BODY_KEY)) {
                // The real response is under the "body" key, which may or may not have been deserialized into
                // a JSONObject already.
                Object body = jsonObject.get(BODY_KEY);
                if (body instanceof String) {
                    JSONTokener tokener = new JSONTokener((String) body);
                    body = tokener.nextValue();
                    if (!(body instanceof JSONObject)) {
                        throw new FacebookException("Got unexpected object type in response");
                    }
                }
                jsonObject = (JSONObject)body;
            }
            GraphObject graphObject = GraphObjectWrapper.wrapJson(jsonObject);
            return new Response(connection, graphObject, null);
        } else {
            throw new FacebookException("Got unexpected object type in response");
        }
    }

    private static List<Response> constructErrorResponses(HttpURLConnection connection, int count, Exception error) {
        List<Response> responses = new ArrayList<Response>(count);
        for (int i = 0; i < count; ++i) {
            Response response = new Response(connection, null, error);
            responses.add(response);
        }
        return responses;
    }
}