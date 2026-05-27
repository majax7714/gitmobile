package com.ajax.relay.service;

import com.ajax.relay.model.InstanceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class Ec2Service {

    private final Ec2Client ec2;

    @Value("${aws.dev-instance-id}")
    private String instanceId;

    public InstanceStatus describe() {
        DescribeInstancesResponse r = ec2.describeInstances(DescribeInstancesRequest.builder()
                .instanceIds(instanceId)
                .build());
        Instance i = r.reservations().getFirst().instances().getFirst();
        return switch (i.state().name()) {
            case PENDING -> InstanceStatus.STARTING;
            case RUNNING -> InstanceStatus.RUNNING;
            case STOPPING, SHUTTING_DOWN -> InstanceStatus.STOPPING;
            default -> InstanceStatus.STOPPED;
        };
    }

    public void start() {
        log.info("Starting EC2 {}", instanceId);
        ec2.startInstances(StartInstancesRequest.builder().instanceIds(instanceId).build());
    }

    public void stop() {
        log.info("Stopping EC2 {}", instanceId);
        ec2.stopInstances(StopInstancesRequest.builder().instanceIds(instanceId).build());
    }

    public String instanceId() {
        return instanceId;
    }
}
