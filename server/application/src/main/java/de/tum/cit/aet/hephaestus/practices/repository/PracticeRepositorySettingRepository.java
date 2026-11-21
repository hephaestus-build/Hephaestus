package de.tum.cit.aet.hephaestus.practices.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PracticeRepositorySettingRepository extends JpaRepository<PracticeRepositorySetting, Long> {}
