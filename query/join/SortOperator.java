package edu.berkeley.cs186.database.query;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.query.disk.Run;
import edu.berkeley.cs186.database.table.Record;
import edu.berkeley.cs186.database.table.Schema;
import edu.berkeley.cs186.database.table.stats.TableStats;

import java.util.*;
import java.util.stream.Collectors;

public class SortOperator extends QueryOperator {
    protected Comparator<Record> comparator;
    private TransactionContext transaction;
    private Run sortedRecords;
    private int numBuffers;
    private int sortColumnIndex;
    private String sortColumnName;

    public SortOperator(TransactionContext transaction, QueryOperator source,
                        String columnName) {
        super(OperatorType.SORT, source);
        this.transaction = transaction;
        this.numBuffers = this.transaction.getWorkMemSize();
        this.sortColumnIndex = getSchema().findField(columnName);
        this.sortColumnName = getSchema().getFieldName(this.sortColumnIndex);
        this.comparator = new RecordComparator();
    }

    private class RecordComparator implements Comparator<Record> {
        @Override
        public int compare(Record r1, Record r2) {
            return r1.getValue(sortColumnIndex).compareTo(r2.getValue(sortColumnIndex));
        }
    }

    @Override
    public TableStats estimateStats() {
        return getSource().estimateStats();
    }

    @Override
    public Schema computeSchema() {
        return getSource().getSchema();
    }

    @Override
    public int estimateIOCost() {
        int N = getSource().estimateStats().getNumPages();
        double pass0Runs = Math.ceil(N / numBuffers);
        double numPasses = 1 + Math.ceil(Math.log(pass0Runs) / Math.log(numBuffers - 1));
        return (int) (2 * N * numPasses);
    }

    @Override
    public String str() {
        return "Sort (cost=" + estimateIOCost() + ")";
    }

    @Override
    public List<String> sortedBy() {
        return Collections.singletonList(sortColumnName);
    }

    @Override
    public boolean materialized() { return true; }

    @Override
    public BacktrackingIterator<Record> backtrackingIterator() {
        if (this.sortedRecords == null) this.sortedRecords = sort();
        return sortedRecords.iterator();
    }

    @Override
    public Iterator<Record> iterator() {
        return backtrackingIterator();
    }

    /**
     * Returns a Run containing records from the input iterator in sorted order.
     * You're free to use an in memory sort over all the records using one of
     * Java's built-in sorting methods.
     *
     * @return a single sorted run containing all the records from the input
     * iterator
     */
    public Run sortRun(Iterator<Record> records) {
        List<Record> recordList = new ArrayList<>();
        records.forEachRemaining(recordList::add);
        Collections.sort(recordList, this.comparator);
        return makeRun(recordList);
    }

    /**
     * Given a list of sorted runs, returns a new run that is the result of
     * merging the input runs. You should use a Priority Queue (java.util.PriorityQueue)
     * to determine which record should be should be added to the output run
     * next.
     *
     * You are NOT allowed to have more than runs.size() records in your
     * priority queue at a given moment. It is recommended that your Priority
     * Queue hold Pair<Record, Integer> objects where a Pair (r, i) is the
     * Record r with the smallest value you are sorting on currently unmerged
     * from run i. `i` can be useful to locate which record to add to the queue
     * next after the smallest element is removed.
     *
     * @return a single sorted run obtained by merging the input runs
     */
    public Run mergeSortedRuns(List<Run> runs) {
        assert (runs.size() <= this.numBuffers - 1);
        PriorityQueue pq = new PriorityQueue(runs.size(), new RecordPairComparator());
        List<Iterator> iteratorList = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            iteratorList.add(runs.get(i).iterator());
            if (iteratorList.get(i).hasNext()) {
                Record record = (Record) iteratorList.get(i).next();
                pq.add(new Pair<>(record, i));
            }
        }

        List<Record> result = new ArrayList<>();
        while (pq.peek() != null) {
            Pair<Record, Integer> min = (Pair<Record, Integer>) pq.poll();
            result.add(min.getFirst());
            int i = min.getSecond();
            if (iteratorList.get(i).hasNext()) {
                Record record = (Record) iteratorList.get(i).next();
                pq.add(new Pair<>(record, i));
            }
        }
        return makeRun(result);
    }

    /**
     * Compares the two (record, integer) pairs based only on the record
     * component using the default comparator. You may find this useful for
     * implementing mergeSortedRuns.
     */
    private class RecordPairComparator implements Comparator<Pair<Record, Integer>> {
        @Override
        public int compare(Pair<Record, Integer> o1, Pair<Record, Integer> o2) {
            return SortOperator.this.comparator.compare(o1.getFirst(), o2.getFirst());
        }
    }

    /**
     * Given a list of N sorted runs, returns a list of sorted runs that is the
     * result of merging (numBuffers - 1) of the input runs at a time. If N is
     * not a perfect multiple of (numBuffers - 1) the last sorted run should be
     * the result of merging less than (numBuffers - 1) runs.
     *
     * @return a list of sorted runs obtained by merging the input runs
     */
    public List<Run> mergePass(List<Run> runs) {
        int numRuns = runs.size() / (this.numBuffers - 1);

        List<Run> result = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < numRuns; i++) {
            result.add(mergeSortedRuns(runs.subList(index, index + (this.numBuffers - 1))));
            index += (this.numBuffers - 1);
        }

        if (runs.size() % (this.numBuffers - 1) > 0) {
            result.add(mergeSortedRuns(runs.subList(index, runs.size())));
        }
        return result;
    }

    /**
     * Does an external merge sl sorted runs.
     *
     * @return a single run containing all of the source operator's records in
     * sorted order.
     */
    public Run sort() {
        // Iterator over the records of the relation we want to sort
        Iterator<Record> sourceIterator = getSource().iterator();

        List<Run> sortedRuns = new ArrayList<>();
        while (sourceIterator.hasNext()) {
            Iterator<Record> pass0 = getBlockIterator(sourceIterator, getSource().getSchema(), numBuffers);
            sortedRuns.add(sortRun(pass0));
        }

        List<Run> result = sortedRuns;
        while(true) {
            if (result.size() == 1) break;
            result = mergePass(result);
        }
        return result.get(0);
    }

    /**
     * @return a new empty run.
     */
    public Run makeRun() {
        return new Run(this.transaction, getSchema());
    }

    /**
     * @param records
     * @return A new run containing the records in `records`
     */
    public Run makeRun(List<Record> records) {
        Run run = new Run(this.transaction, getSchema());
        run.addAll(records);
        return run;
    }
}

