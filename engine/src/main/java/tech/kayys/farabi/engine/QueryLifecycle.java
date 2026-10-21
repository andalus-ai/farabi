package tech.kayys.farabi.engine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.kayys.farabi.engine.QueryContracts.Status;

/** Provider-neutral remote job state, result paging and cancellation. */
public final class QueryLifecycle {
    private QueryLifecycle() {}
    public record RemoteState(Status status,String diagnostic) {
        public RemoteState {
            Objects.requireNonNull(status);
            if(status==Status.PENDING || status==Status.SUBMITTED)
                throw new IllegalArgumentException("remote state must be running or terminal");
        }
    }
    public record ResultPage(List<String> columns,List<Map<String,Object>> rows,int offset,boolean hasMore) {
        public ResultPage {
            columns=List.copyOf(Objects.requireNonNull(columns));
            rows=List.copyOf(Objects.requireNonNull(rows).stream().map(Map::copyOf).toList());
            if(offset<0) throw new IllegalArgumentException("negative offset");
        }
    }
    public interface Provider {
        RemoteState status(String providerJobId);
        ResultPage results(String providerJobId,int offset,int limit);
        void cancel(String providerJobId);
    }
}
