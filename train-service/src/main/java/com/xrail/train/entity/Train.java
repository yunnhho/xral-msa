package com.xrail.train.entity;

import com.xrail.common.entity.BaseTimeEntity;
import com.xrail.train.entity.enums.TrainType;
import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(name = "trains")
public class Train extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "train_id")
    private Long trainId;

    @Column(name = "train_number", nullable = false, unique = true, length = 20)
    private String trainNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "train_type", nullable = false, length = 20)
    private TrainType trainType;
}
