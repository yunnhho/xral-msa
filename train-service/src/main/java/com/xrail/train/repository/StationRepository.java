package com.xrail.train.repository;

import com.xrail.train.entity.Station;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationRepository extends JpaRepository<Station, Long> {}
