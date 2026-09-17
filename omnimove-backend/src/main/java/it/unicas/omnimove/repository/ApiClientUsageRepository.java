package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.ApiClientUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ApiClientUsageRepository extends JpaRepository<ApiClientUsage, Long> {

    long countByClientId(Long clientId);

    /** [method, endpoint, count], most used first. */
    @Query("select u.method, u.endpoint, count(u) from ApiClientUsage u "
         + "where u.clientId = :clientId group by u.method, u.endpoint order by count(u) desc")
    List<Object[]> countByEndpoint(@Param("clientId") Long clientId);

    /** [day as java.sql.Date, count] since {@code since}, oldest first. */
    @Query(value = "select date(called_at at time zone 'Europe/Rome') as d, count(*) "
                 + "from api_client_usage where client_id = :clientId and called_at >= :since "
                 + "group by d order by d", nativeQuery = true)
    List<Object[]> countByDay(@Param("clientId") Long clientId, @Param("since") Instant since);
}
