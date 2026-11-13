"""Reject omitted residual rows and superseded U+O obligation totals."""
import copy
from pathlib import Path
import re
import tempfile
import unittest
import summarize_joint_counts as summary
from publish_joint2_counts import partitions

PAPER=Path(__file__).resolve().parents[2]/'docs/td20-reduction-count.tex'


class ActiveSummaryTest(unittest.TestCase):
    def setUp(self):
        self.source=PAPER.read_text()
        rows=[]
        for match in re.finditer(r'^\\jointrow\{(\d+)\}\{([0-9,]+)\}\{(\d+)\}\{(\d+)\}\{(\d+)\}\{(\d+)\}$',self.source,re.M):
            rows.append(dict(zip(('number','partition','U','J','C','O'),
                (int(match[1]),list(map(int,match[2].split(','))),*map(int,match.group(3,4,5,6))))))
        self.report={'format':'td20-joint2-selection-summary-v1','rows':rows}
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.paper=Path(self.temp.name)/'paper.tex';self.paper.write_text(self.source)

    def test_active_total_uses_residuals(self):
        result=summary.reconcile(self.report,self.paper)
        self.assertEqual(124395153,result['totally_unoriented_residual_root_classes'])
        self.assertEqual(2146972300,result['direct_root_class_obligations'])
        self.assertNotEqual(sum(r['U']+r['O'] for r in self.report['rows']),result['direct_root_class_obligations'])

    def test_missing_duplicate_pending_and_over_census_reject(self):
        row=r'\unorientedresidualrow{1}{0}'
        for text in [self.source.replace(row,''),self.source.replace(row,row+'\n'+row),
                     self.source.replace(row,r'\unorientedresidualrow{1}{\pending}'),
                     self.source.replace(row,r'\unorientedresidualrow{1}{1}')]:
            self.paper.write_text(text)
            with self.assertRaises(ValueError):summary.reconcile(self.report,self.paper)

    def test_changed_oriented_count_rejects(self):
        self.report['rows'][0]['J']+=1
        with self.assertRaises(ValueError):summary.reconcile(self.report,self.paper)


if __name__=='__main__':unittest.main()
