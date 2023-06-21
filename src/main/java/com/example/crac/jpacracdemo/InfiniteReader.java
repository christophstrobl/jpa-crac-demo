/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.crac.jpacracdemo;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.stereotype.Component;

/**
 * @author Christoph Strobl
 * @since 2023/06
 */
@Component
public class InfiniteReader implements CommandLineRunner, SmartLifecycle {

	PersonRepository repository;
	AtomicBoolean run = new AtomicBoolean(false);
	DataSource dataSource;
	ApplicationContext ctx;

	public InfiniteReader(PersonRepository repository, DataSource dataSource, ApplicationContext ctx) {
		this.repository = repository;
		this.dataSource = dataSource;
		this.ctx = ctx;
	}

	@Override
	public void run(String... args) throws Exception {

//		DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
//		processor.setBeanFactory(((AnnotationConfigApplicationContext) ctx).getBeanFactory());
//		processor.stop();

		while (isRunning()) {
			System.out.println(repository.findAll());
			Thread.sleep(2000);
		}
	}

	@Override
	public void start() {

		try {

			Person person = new Person();
			person.id = "id-1";
			person.name = "me";
			repository.save(person);

			System.out.println("saved: " + person);

			if(!isRunning()) {
				run.set(true);
			}

		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void stop() {
		run.set(false);
	}

	@Override
	public boolean isRunning() {
		return run.get();
	}
}
