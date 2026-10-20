package tech.kayys.farabi.engine;

import java.util.*;
import java.util.regex.Pattern;
import tech.kayys.farabi.engine.QueryContracts.*;
import tech.kayys.farabi.engine.QueryPorts.Compiler;

/**
 * Compiles an intentionally narrow semantic query grammar:
 * semanticQuery = "datasetId:measureId" (no arbitrary SQL).
 * Dataset and measure definitions are trusted, server-controlled metadata.
 */
public final class GovernedSemanticCompiler implements Compiler {
    public record Dataset(String id, String sourceId, String qualifiedTable, Map<String,String> measures) {
        public Dataset {
            QueryContracts.required(id,"id"); QueryContracts.required(sourceId,"sourceId");
            validateIdentifierPath(qualifiedTable);
            measures=Map.copyOf(Objects.requireNonNull(measures));
            if(measures.isEmpty()) throw new IllegalArgumentException("at least one measure required");
            measures.forEach((name,column)->{
                validateIdentifier(name);validateIdentifier(column);
            });
        }
    }
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private final Map<String,Dataset> datasets;
    public GovernedSemanticCompiler(Collection<Dataset> datasets) {
        Objects.requireNonNull(datasets);
        Map<String,Dataset> result=new HashMap<>();
        for(Dataset dataset:datasets) {
            Objects.requireNonNull(dataset);
            if(result.putIfAbsent(dataset.id(),dataset)!=null) throw new IllegalArgumentException("duplicate dataset "+dataset.id());
        }
        this.datasets=Map.copyOf(result);
    }
    @Override public Plan compile(Request request) {
        Objects.requireNonNull(request);
        if(!request.parameters().isEmpty()) throw new IllegalArgumentException("parameters not supported by this compiler");
        String[] tokens=request.semanticQuery().split(":",-1);
        if(tokens.length!=2) throw new IllegalArgumentException("expected dataset:measure");
        Dataset dataset=datasets.get(tokens[0]);
        if(dataset==null) throw new IllegalArgumentException("unknown dataset");
        if(!dataset.sourceId().equals(request.sourceId())) throw new IllegalArgumentException("dataset source mismatch");
        String column=dataset.measures().get(tokens[1]);
        if(column==null) throw new IllegalArgumentException("unknown measure");
        // All SQL identifiers originate from validated, trusted catalog metadata.
        return new Plan(dataset.sourceId(),"SELECT SUM("+column+") AS value FROM "+dataset.qualifiedTable());
    }
    private static void validateIdentifier(String identifier) {
        if(identifier==null||!IDENTIFIER.matcher(identifier).matches())
            throw new IllegalArgumentException("invalid catalog identifier");
    }
    private static void validateIdentifierPath(String path) {
        if(path==null||path.isBlank()) throw new IllegalArgumentException("table required");
        for(String part:path.split("\\.",-1)) validateIdentifier(part);
    }
}
