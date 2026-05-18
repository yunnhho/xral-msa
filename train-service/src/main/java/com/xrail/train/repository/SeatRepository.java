package com.xrail.train.repository;

import com.xrail.train.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("SELECT s FROM Seat s JOIN FETCH s.carriage c JOIN FETCH c.train t WHERE c.train.trainId = :trainId")
    List<Seat> findAllByTrainId(Long trainId);
}
