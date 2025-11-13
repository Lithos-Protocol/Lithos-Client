package stratum;

public class NewBlock extends JobManagerEvent {
    public BlockTemplate blockTemplate;
    public NewBlock(BlockTemplate blockTemplate) {
        super();
        this.blockTemplate = blockTemplate;
    }
}
