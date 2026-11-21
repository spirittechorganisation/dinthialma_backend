package com.africa.dinthialma_backend.config;

import com.africa.dinthialma_backend.common.util.Fuseau;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Horloge de l'application, heure de Dakar. Injectée là où une règle dépend de la date du jour (ex.
 * date de début passée, B46), pour que les tests puissent fixer « aujourd'hui ».
 */
@Configuration
public class HorlogeConfig {

  @Bean
  public Clock horloge() {
    return Clock.system(Fuseau.DAKAR);
  }
}
