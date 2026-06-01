package com.xrail.train.entity;

import com.xrail.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

@Getter
@Entity
@Table(name = "train_dlt_alert_logs")
public class DltAlertLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false)
    private int partition;

    @Column(nullable = false)
    private long offset;

    @Column(name = "record_key", length = 255)
    private String recordKey;

    @Column(name = "record_value", columnDefinition = "TEXT")
    private String recordValue;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    protected DltAlertLog() {}

    @Builder
    public DltAlertLog(String topic, int partition, long offset,
                       String recordKey, String recordValue, String errorMessage) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.recordKey = recordKey;
        this.recordValue = recordValue;
        this.errorMessage = errorMessage;
    }
}
