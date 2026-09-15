package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {

    Optional<ApiClient> findByKeyHash(String keyHash);

    List<ApiClient> findAllByOrderByCreatedAtDesc();
}
