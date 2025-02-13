/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.bpmn;

import io.camunda.zeebe.engine.processing.bpmn.behavior.BpmnBehaviors;
import io.camunda.zeebe.engine.processing.bpmn.container.CallActivityProcessor;
import io.camunda.zeebe.engine.processing.bpmn.container.EventSubProcessProcessor;
import io.camunda.zeebe.engine.processing.bpmn.container.MultiInstanceBodyProcessor;
import io.camunda.zeebe.engine.processing.bpmn.container.ProcessProcessor;
import io.camunda.zeebe.engine.processing.bpmn.container.SubProcessProcessor;
import io.camunda.zeebe.engine.processing.bpmn.event.BoundaryEventProcessor;
import io.camunda.zeebe.engine.processing.bpmn.event.EndEventProcessor;
import io.camunda.zeebe.engine.processing.bpmn.event.IntermediateCatchEventProcessor;
import io.camunda.zeebe.engine.processing.bpmn.event.IntermediateThrowEventProcessor;
import io.camunda.zeebe.engine.processing.bpmn.event.StartEventProcessor;
import io.camunda.zeebe.engine.processing.bpmn.gateway.EventBasedGatewayProcessor;
import io.camunda.zeebe.engine.processing.bpmn.gateway.ExclusiveGatewayProcessor;
import io.camunda.zeebe.engine.processing.bpmn.gateway.ParallelGatewayProcessor;
import io.camunda.zeebe.engine.processing.bpmn.task.BusinessRuleTaskProcessor;
import io.camunda.zeebe.engine.processing.bpmn.task.JobWorkerTaskProcessor;
import io.camunda.zeebe.engine.processing.bpmn.task.ManualTaskProcessor;
import io.camunda.zeebe.engine.processing.bpmn.task.ReceiveTaskProcessor;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import java.util.EnumMap;
import java.util.Map;

public final class BpmnElementProcessors {

  private final Map<BpmnElementType, BpmnElementProcessor<?>> processors =
      new EnumMap<>(BpmnElementType.class);

  public BpmnElementProcessors(final BpmnBehaviors bpmnBehaviors) {
    // tasks
    processors.put(BpmnElementType.SERVICE_TASK, new JobWorkerTaskProcessor(bpmnBehaviors));
    processors.put(
        BpmnElementType.BUSINESS_RULE_TASK, new BusinessRuleTaskProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.SCRIPT_TASK, new JobWorkerTaskProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.SEND_TASK, new JobWorkerTaskProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.USER_TASK, new JobWorkerTaskProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.RECEIVE_TASK, new ReceiveTaskProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.MANUAL_TASK, new ManualTaskProcessor(bpmnBehaviors));

    // gateways
    processors.put(BpmnElementType.EXCLUSIVE_GATEWAY, new ExclusiveGatewayProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.PARALLEL_GATEWAY, new ParallelGatewayProcessor(bpmnBehaviors));
    processors.put(
        BpmnElementType.EVENT_BASED_GATEWAY, new EventBasedGatewayProcessor(bpmnBehaviors));

    // containers
    processors.put(BpmnElementType.PROCESS, new ProcessProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.SUB_PROCESS, new SubProcessProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.EVENT_SUB_PROCESS, new EventSubProcessProcessor(bpmnBehaviors));
    processors.put(
        BpmnElementType.MULTI_INSTANCE_BODY, new MultiInstanceBodyProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.CALL_ACTIVITY, new CallActivityProcessor(bpmnBehaviors));

    // events
    processors.put(BpmnElementType.START_EVENT, new StartEventProcessor(bpmnBehaviors));
    processors.put(
        BpmnElementType.INTERMEDIATE_CATCH_EVENT,
        new IntermediateCatchEventProcessor(bpmnBehaviors));
    processors.put(
        BpmnElementType.INTERMEDIATE_THROW_EVENT,
        new IntermediateThrowEventProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.END_EVENT, new EndEventProcessor(bpmnBehaviors));
    processors.put(BpmnElementType.BOUNDARY_EVENT, new BoundaryEventProcessor(bpmnBehaviors));
  }

  public <T extends ExecutableFlowElement> BpmnElementProcessor<T> getProcessor(
      final BpmnElementType bpmnElementType) {

    final var processor = (BpmnElementProcessor<T>) processors.get(bpmnElementType);
    if (processor == null) {
      throw new UnsupportedOperationException(
          String.format(
              "Expected to find a BPMN element processor for the BPMN element type '%s' but not found.",
              bpmnElementType));
    }
    return processor;
  }

  public <T extends ExecutableFlowElement> BpmnElementContainerProcessor<T> getContainerProcessor(
      final BpmnElementType bpmnElementType) {
    final var processor = processors.get(bpmnElementType);
    if (processor instanceof BpmnElementContainerProcessor) {
      return (BpmnElementContainerProcessor<T>) processor;
    }
    throw new UnsupportedOperationException(
        String.format(
            "Expected to find a BPMN container processor for the BPMN element type '%s' but not found.",
            bpmnElementType));
  }
}
