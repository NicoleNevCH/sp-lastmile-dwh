package com.transportadora.chaos.service;

import net.datafaker.Faker;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates recipient and São Paulo address data. Neighborhoods are drawn from
 * a real list so the regional grain that survives PII masking is meaningful.
 */
@Component
public class RecipientFaker {

    private final Faker faker = new Faker(new Locale("pt", "BR"));

    private static final String[] SP_NEIGHBORHOODS = {
            "Pinheiros", "Vila Mariana", "Moema", "Tatuapé", "Santana",
            "Itaim Bibi", "Bela Vista", "Consolação", "Liberdade", "Mooca",
            "Lapa", "Perdizes", "Vila Madalena", "Brooklin", "Saúde",
            "Ipiranga", "Santo Amaro", "Penha", "Butantã", "Jabaquara",
            "Sé", "República", "Higienópolis", "Vila Prudente", "Sapopemba"
    };

    public record Recipient(
            String name, String cpf, String street, String houseNumber,
            String neighborhood, String cep, String city
    ) {}

    public Recipient next() {
        String name = faker.name().fullName();
        String cpf = faker.cpf().valid();
        String street = faker.address().streetName();
        String houseNumber = String.valueOf(ThreadLocalRandom.current().nextInt(1, 4500));
        String neighborhood = SP_NEIGHBORHOODS[ThreadLocalRandom.current().nextInt(SP_NEIGHBORHOODS.length)];
        String cep = randomCep();
        return new Recipient(name, cpf, street, houseNumber, neighborhood, cep, "São Paulo");
    }

    /** SP CEPs start with 0; format NNNNN-NNN. The 5-digit prefix is the grain
     *  exposed downstream. */
    private String randomCep() {
        int prefix = ThreadLocalRandom.current().nextInt(1000, 9999); // 01000–09999
        int suffix = ThreadLocalRandom.current().nextInt(0, 1000);
        return String.format("0%04d-%03d", prefix, suffix);
    }
}
