package stratum;

public class UpdatedBlock extends JobManagerEvent {
    public BlockTemplate blockTemplate;
    public UpdatedBlock(BlockTemplate blockTemplate) {
        super();
        this.blockTemplate = blockTemplate;
    }
}
