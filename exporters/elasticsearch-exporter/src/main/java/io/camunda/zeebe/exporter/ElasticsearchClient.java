/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.exporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.exporter.dto.BulkIndexAction;
import io.camunda.zeebe.exporter.dto.BulkItemError;
import io.camunda.zeebe.exporter.dto.BulkResponse;
import io.camunda.zeebe.exporter.dto.PutIndexTemplateResponse;
import io.camunda.zeebe.exporter.dto.Template;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.ValueType;
import io.prometheus.client.Histogram;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.http.entity.EntityTemplate;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;

class ElasticsearchClient {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RestClient client;
  private final ElasticsearchExporterConfiguration configuration;
  private final TemplateReader templateReader;
  private final RecordIndexRouter indexRouter;
  private final BulkIndexRequest bulkIndexRequest;

  private ElasticsearchMetrics metrics;

  ElasticsearchClient(final ElasticsearchExporterConfiguration configuration) {
    this(configuration, new BulkIndexRequest());
  }

  ElasticsearchClient(
      final ElasticsearchExporterConfiguration configuration,
      final BulkIndexRequest bulkIndexRequest) {
    this(configuration, bulkIndexRequest, null);
  }

  ElasticsearchClient(
      final ElasticsearchExporterConfiguration configuration,
      final BulkIndexRequest bulkIndexRequest,
      final ElasticsearchMetrics metrics) {
    this.configuration = configuration;
    this.bulkIndexRequest = bulkIndexRequest;
    this.metrics = metrics;

    templateReader = new TemplateReader(configuration.index);
    indexRouter = new RecordIndexRouter(configuration.index);
    client = RestClientFactory.of(configuration);
  }

  public void close() throws IOException {
    client.close();
  }

  public void index(final Record<?> record) {
    if (metrics == null) {
      metrics = new ElasticsearchMetrics(record.getPartitionId());
    }

    final BulkIndexAction action =
        new BulkIndexAction(
            indexRouter.indexFor(record),
            indexRouter.idFor(record),
            indexRouter.routingFor(record));
    bulkIndexRequest.index(action, record);
  }

  /**
   * Flushes the bulk request to Elastic, unless it's currently empty.
   *
   * @throws ElasticsearchExporterException if not all items of the bulk were flushed successfully
   */
  public void flush() {
    if (bulkIndexRequest.isEmpty()) {
      return;
    }

    metrics.recordBulkSize(bulkIndexRequest.size());
    metrics.recordBulkMemorySize(bulkIndexRequest.memoryUsageBytes());

    try (final Histogram.Timer ignored = metrics.measureFlushDuration()) {
      exportBulk();

      // all records where flushed, create new bulk request, otherwise retry next time
      bulkIndexRequest.clear();
    } catch (final ElasticsearchExporterException e) {
      metrics.recordFailedFlush();
      throw e;
    }
  }

  /**
   * Returns whether the exporter should call {@link #flush()} or not.
   *
   * @return true if {@link #flush()} should be called, false otherwise
   */
  public boolean shouldFlush() {
    return bulkIndexRequest.memoryUsageBytes() >= configuration.bulk.memoryLimit
        || bulkIndexRequest.size() >= configuration.bulk.size;
  }

  /**
   * Creates an index template for the given value type, read from the resources.
   *
   * @return true if request was acknowledged
   */
  public boolean putIndexTemplate(final ValueType valueType) {
    final String templateName = indexRouter.indexPrefixForValueType(valueType);
    final Template template =
        templateReader.readIndexTemplate(
            valueType,
            indexRouter.searchPatternForValueType(valueType),
            indexRouter.aliasNameForValueType(valueType));

    return putIndexTemplate(templateName, template);
  }

  /**
   * Creates or updates the component template on the target Elasticsearch. The template is read
   * from {@link TemplateReader#readComponentTemplate()}.
   */
  public boolean putComponentTemplate() {
    final Template template = templateReader.readComponentTemplate();
    return putComponentTemplate(template);
  }

  private void exportBulk() {
    final Response httpResponse;
    try {
      final var request = new Request("POST", "/_bulk");
      final var body = new EntityTemplate(bulkIndexRequest);
      body.setContentType("application/x-ndjson");
      request.setEntity(body);

      httpResponse = client.performRequest(request);
    } catch (final ResponseException e) {
      throw new ElasticsearchExporterException("Elastic returned an error response on flush", e);
    } catch (final IOException e) {
      throw new ElasticsearchExporterException("Failed to flush bulk", e);
    }

    final BulkResponse bulkResponse;
    try {
      bulkResponse = MAPPER.readValue(httpResponse.getEntity().getContent(), BulkResponse.class);
    } catch (final IOException e) {
      throw new ElasticsearchExporterException("Failed to parse response when flushing", e);
    }

    if (bulkResponse.hasErrors()) {
      throwCollectedBulkError(bulkResponse);
    }
  }

  private void throwCollectedBulkError(final BulkResponse bulkResponse) {
    final var collectedErrors = new ArrayList<String>();
    bulkResponse.getItems().stream()
        .flatMap(item -> Optional.ofNullable(item.getIndex()).stream())
        .flatMap(index -> Optional.ofNullable(index.getError()).stream())
        .collect(Collectors.groupingBy(BulkItemError::getType))
        .forEach(
            (errorType, errors) ->
                collectedErrors.add(
                    String.format(
                        "Failed to flush %d item(s) of bulk request [type: %s, reason: %s]",
                        errors.size(), errorType, errors.get(0).getReason())));

    throw new ElasticsearchExporterException("Failed to flush bulk request: " + collectedErrors);
  }

  private boolean putIndexTemplate(final String templateName, final Template template) {
    try {
      final var request = new Request("PUT", "/_index_template/" + templateName);
      request.setJsonEntity(MAPPER.writeValueAsString(template));

      final var response = client.performRequest(request);
      final var putIndexTemplateResponse =
          MAPPER.readValue(response.getEntity().getContent(), PutIndexTemplateResponse.class);
      return putIndexTemplateResponse.acknowledged();
    } catch (final IOException e) {
      throw new ElasticsearchExporterException("Failed to put index template", e);
    }
  }

  private boolean putComponentTemplate(final Template template) {
    try {
      final var request = new Request("PUT", "/_component_template/" + configuration.index.prefix);
      request.setJsonEntity(MAPPER.writeValueAsString(template));

      final var response = client.performRequest(request);
      final var putIndexTemplateResponse =
          MAPPER.readValue(response.getEntity().getContent(), PutIndexTemplateResponse.class);
      return putIndexTemplateResponse.acknowledged();
    } catch (final IOException e) {
      throw new ElasticsearchExporterException("Failed to put component template", e);
    }
  }
}
