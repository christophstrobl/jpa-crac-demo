package com.example.crac.jpacracdemo;

import org.springframework.data.repository.ListCrudRepository;

/**
 * @author Christoph Strobl
 * @since 2023/06
 */
public interface PersonRepository extends ListCrudRepository<Person,String> {

}
