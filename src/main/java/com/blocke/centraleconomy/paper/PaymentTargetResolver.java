package com.blocke.centraleconomy.paper;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface PaymentTargetResolver {
    CompletionStage<Optional<PaymentTarget>> resolveExact(String name);
    List<String> suggestions(String prefix);
}
