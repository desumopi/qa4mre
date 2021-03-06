package edu.cmu.lti.deiis.hw5.answer_ranking;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.FSList;
import org.apache.uima.resource.ResourceInitializationException;

import edu.cmu.lti.oaqa.core.provider.solr.SolrWrapper;
import edu.cmu.lti.qalab.types.Answer;
import edu.cmu.lti.qalab.types.CandidateAnswer;
import edu.cmu.lti.qalab.types.CandidateSentence;
import edu.cmu.lti.qalab.types.NER;
import edu.cmu.lti.qalab.types.NounPhrase;
import edu.cmu.lti.qalab.types.Question;
import edu.cmu.lti.qalab.types.QuestionAnswerSet;
import edu.cmu.lti.qalab.types.TestDocument;
import edu.cmu.lti.qalab.utils.Utils;

public class AnswerChoiceCandAnsPMIScorer extends JCasAnnotator_ImplBase {

  private SolrWrapper solrWrapper;

  HashSet<String> hshStopWords = new HashSet<String>();

  int K_CANDIDATES = 5;
  double tFreqWeight = 1.0;

  @Override
  public void initialize(UimaContext context) throws ResourceInitializationException {
    super.initialize(context);
    String serverUrl = (String) context.getConfigParameterValue("SOLR_SERVER_URL");
    K_CANDIDATES = (Integer) context.getConfigParameterValue("K_CANDIDATES");

    try {
      this.solrWrapper = new SolrWrapper(serverUrl);
      // loadStopWords(stopFile);
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  @Override
  public void process(JCas aJCas) throws AnalysisEngineProcessException {

    TestDocument testDoc = Utils.getTestDocumentFromCAS(aJCas);
    // String testDocId = testDoc.getId();
    ArrayList<QuestionAnswerSet> qaSet = Utils.getQuestionAnswerSetFromTestDocCAS(aJCas);

    for (int i = 0; i < qaSet.size(); i++) {

      Question question = qaSet.get(i).getQuestion();
      System.out.println("Question: " + question.getText());
      ArrayList<Answer> choiceList = Utils.fromFSListToCollection(qaSet.get(i).getAnswerList(),
              Answer.class);

      // callie Remove the sentences for which isDiscard is true
      for (int ind = choiceList.size() - 1; ind >= 0; ind--) {
        Answer temp = choiceList.get(ind);
        if (temp.getIsDiscard()) {
          choiceList.remove(ind);
        }
      }
      
      //napat in case all choice is discard recover them
      if(choiceList.isEmpty())
      {
        choiceList = Utils.fromFSListToCollection(qaSet.get(i).getAnswerList(),
                Answer.class);
      }
      
      ArrayList<CandidateSentence> candSentList = Utils.fromFSListToCollection(qaSet.get(i)
              .getCandidateSentenceList(), CandidateSentence.class);

      int topK = Math.min(K_CANDIDATES, candSentList.size());
      for (int c = 0; c < topK; c++) {

        CandidateSentence candSent = candSentList.get(c);

        ArrayList<NounPhrase> candSentNouns = Utils.fromFSListToCollection(candSent.getSentence()
                .getPhraseList(), NounPhrase.class);
        ArrayList<NER> candSentNers = Utils.fromFSListToCollection(candSent.getSentence()
                .getNerList(), NER.class);

        ArrayList<CandidateAnswer> candAnsList = new ArrayList<CandidateAnswer>();
        for (int j = 0; j < choiceList.size(); j++) {
          double score1 = 0.0;
          Answer answer = choiceList.get(j);
          int count = 0;
          for (int k = 0; k < candSentNouns.size(); k++) {
            try {
              score1 += scoreCoOccurInSameDoc(candSentNouns.get(k).getText(), choiceList.get(j));
              count++;
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
          for (int k = 0; k < candSentNers.size(); k++) {

            try {
              score1 += scoreCoOccurInSameDoc(candSentNers.get(k).getText(), choiceList.get(j));
              count++;
            } catch (Exception e) {
              e.printStackTrace();
            }

          }

          // napat add extra score on matching question/answer
          ArrayList<NounPhrase> questionSentNouns = Utils.fromFSListToCollection(
                  question.getNounList(), NounPhrase.class);
          ArrayList<NER> questionSentNers = Utils.fromFSListToCollection(question.getNerList(),
                  NER.class);
          for (int k = 0; k < questionSentNouns.size(); k++) {
            try {
              double tmScore = scoreCoOccurInSameDoc(questionSentNouns.get(k).getText(),
                      choiceList.get(j));
              score1 += tmScore / topK;
              count++;
              // System.out.println("qnoun:"+tmScore);
            } catch (Exception e) {
              e.printStackTrace();
            }

          }
          for (int k = 0; k < questionSentNers.size(); k++) {
            try {
              double tmScore = scoreCoOccurInSameDoc(questionSentNers.get(k).getText(),
                      choiceList.get(j));
              score1 += tmScore / topK;
              count++;
              // System.out.println("qner:"+tmScore);
            } catch (Exception e) {
              e.printStackTrace();
            }

          }
          
          // callie: count term frequency of answer in document, use frequency to change weight
          // note that "frequency" is a misnomer: this is really the count of the answer in the
          // document multiplied by some weight
          double tFreq = getTermCount(answer.getText(), testDoc.getText());
          score1 += tFreqWeight*tFreq;

          // Wenyi added "count" to normalize the original the PMI score, which is a sum of scores.
          // the result of PMI itself stays the same; but when combined with default and alternative
          // similarity scorers, the performance get worse. so we may ignore the variable "count".

          // callie: I removed this println
          // System.out.println(choiceList.get(j).getText() + "\t"
          // + score1 + "\t" + ((score1)));

          CandidateAnswer candAnswer = null;
          if (candSent.getCandAnswerList() == null) {
            candAnswer = new CandidateAnswer(aJCas);
          } else {
            candAnswer = Utils.fromFSListToCollection(candSent.getCandAnswerList(),
                    CandidateAnswer.class).get(j);// new CandidateAnswer(aJCas);;
          }
          candAnswer.setText(answer.getText());
          candAnswer.setQId(answer.getQuestionId());
          candAnswer.setChoiceIndex(j);
          candAnswer.setPMIScore(1.0 * score1); // add count here to normalize
          candAnsList.add(candAnswer);
        }
        FSList fsCandAnsList = Utils.fromCollectionToFSList(aJCas, candAnsList);
        candSent.setCandAnswerList(fsCandAnsList);
        candSentList.set(c, candSent);
      }

      System.out.println("================================================");
      FSList fsCandSentList = Utils.fromCollectionToFSList(aJCas, candSentList);
      qaSet.get(i).setCandidateSentenceList(fsCandSentList);

    }
    FSList fsQASet = Utils.fromCollectionToFSList(aJCas, qaSet);
    testDoc.setQaList(fsQASet);

  }

  public static boolean isNumeric(String str) {
    try {
      double d = Double.parseDouble(str);
      if (d != (int) d) {
        return false;
      }
    } catch (NumberFormatException nfe) {
      return false;
    }
    return true;
  }

  public static int getFirstSpace(String st) {
    Character space = ' ';
    for (int i = 0; i < st.length(); i++) {
      Character tmp = st.charAt(i);
      if (tmp.equals(space)) {
        return i;
      }
    }
    return st.length() - 1;
  }
  
  // callie (for term frequency)
  public static double getTermCount(String term, String doc) {
    String t = term.toLowerCase();
    String d = doc.toLowerCase();
    double count=0.0;
    double len = 0.0;
    for (int i = 0; i < doc.length()-t.length(); i++) {
      String dsub = d.substring(i,i+t.length());
      if (dsub.contains(t)) {
        count=count + 1.0;
      }
      if (d.substring(i,i+1).equals(" ")) {
        len = len + 1.0;
      }
    }
    return count;
  }

  public double scoreCoOccurInSameDoc(String question, Answer choice) throws Exception {
    // String choiceTokens[] = choice.split("[ ]");
    ArrayList<NounPhrase> choiceNounPhrases = Utils.fromFSListToCollection(
            choice.getNounPhraseList(), NounPhrase.class);
    double score = 0.0;

    for (int i = 0; i < choiceNounPhrases.size(); i++) {
      // score1(choicei) = hits(problem AND choicei) / hits(choicei)
      String choiceNounPhrase = choiceNounPhrases.get(i).getText();
      if (question.split("[ ]").length > 1) {
        question = "\"" + question + "\"";
      }
      if (choiceNounPhrase.split("[ ]").length > 1) {
        choiceNounPhrase = "\"" + choiceNounPhrase + "\"";
      }

      String query = question + " AND " + choiceNounPhrase;
      // System.out.println(query);
      HashMap<String, String> params = new HashMap<String, String>();
      params.put("q", query);
      params.put("rows", "1");
      SolrParams solrParams = new MapSolrParams(params);
      QueryResponse rsp = null;
      long combinedHits = 0;
      try {
        rsp = solrWrapper.getServer().query(solrParams);
        combinedHits = rsp.getResults().getNumFound();
      } catch (Exception e) {
        // System.out.println(e + "\t" + query);
      }

      // System.out.println(query+"\t"+combinedHits);

      query = choiceNounPhrase;
      // System.out.println(query);
      params = new HashMap<String, String>();
      params.put("q", query);
      params.put("rows", "1");
      solrParams = new MapSolrParams(params);

      long nHits1 = 0;
      try {
        rsp = solrWrapper.getServer().query(solrParams);
        nHits1 = rsp.getResults().getNumFound();
      } catch (Exception e) {
        // System.out.println(e+"\t"+query);
      }
      // System.out.println(query+"\t"+nHits1);

      query = question;
      // System.out.println(query);
      params = new HashMap<String, String>();
      params.put("q", query);
      params.put("rows", "1");
      solrParams = new MapSolrParams(params);
      rsp = solrWrapper.getServer().query(solrParams);
      long nHits2 = rsp.getResults().getNumFound();
      // callie (I removed this println so that all the output fits on the console)
      // System.out.println(query+"\t"+nHits2);

      score += myLog(combinedHits, nHits1, nHits2);
      if (nHits1 != 0) {
        score += (double) combinedHits / nHits1;
      }
    }
    if (choiceNounPhrases.size() > 0) {
      score = score / choiceNounPhrases.size();
    }
    return score;
  }

  public double myLog(long combined, long nHits1, long nHits2) {
    if (combined == 0 || nHits1 == 0 || nHits2 == 0) {
      return 0;
    }
    double logValue = Math.log(combined) - Math.log(nHits1) - Math.log(nHits2);
    return logValue;
  }

}
