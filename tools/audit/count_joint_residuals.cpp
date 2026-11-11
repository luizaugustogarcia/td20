// Exact independent counter for roots with no favorable joint-3 reduction.
// Build: c++ -O3 -std=c++20 tools/audit/count_joint_residuals.cpp -o /tmp/td20-joint-count
// This counts root classes, not sorting certificates or reduction endpoints.
// Totally unoriented roots are excluded and remain separate direct obligations.
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>
namespace {
constexpr int MAX_N=21;
using Perm=std::array<int,MAX_N>;
struct Triple {int a,b,c;};
struct PairConstraint {Triple first,second;};
struct PathConstraint {Triple first,second,third;unsigned q_witness;};
struct PairGroup {Triple first;std::vector<Triple> seconds;};
struct ThirdWitness {Triple third;unsigned q_witness;};
struct SecondGroup {Triple second;std::vector<ThirdWitness> thirds;};
struct PathGroup {Triple first;std::vector<SecondGroup> seconds;};
int odd_cycles(const Perm& p,int n) {
 unsigned seen=0;int count=0;
 for(int x=0;x<n;++x)if(!(seen&(1u<<x))){int y=x,length=0;do{seen|=1u<<y;++length;y=p[y];}while(y!=x);count+=length&1;}
 return count;
}
bool ordered(int a,int b,int c,int n){return(b-a+n)%n<(c-a+n)%n;}
// Position in tau*beta, anchored at a, for an applicable tau=(a,b,c).
int after_position(int x,int a,int b,int c,int n){
 int d=(x-a+n)%n,db=(b-a+n)%n,dc=(c-a+n)%n;
 if(d<db)return d;if(d>=dc)return db+d-dc;return n-dc+d;
}
int gain(const Perm& p,Triple t){return(p[t.a]==t.b)+(p[t.b]==t.c)+(p[t.c]==t.a);}
Perm apply_move(const Perm& p,Triple t){Perm q=p;q[t.a]=p[t.c];q[t.b]=p[t.a];q[t.c]=p[t.b];return q;}
bool full_target(const Perm& p,int n){unsigned seen=0;int x=0;for(int i=0;i<n;++i){if(seen&(1u<<x))return false;seen|=1u<<x;x=p[(x+1)%n];}return x==0;}
bool unoriented(const Perm& p,int n){
 unsigned seen=0;
 for(int x=0;x<n;++x)if(!(seen&(1u<<x))){int y=x,increases=0,length=0;do{seen|=1u<<y;++length;increases+=p[y]>y;y=p[y];}while(y!=x);if(length>=3&&increases!=1)return false;}
 return true;
}
bool totally_unoriented(const Perm& p,const Perm& beta,int n){
 Perm positioned{};std::array<int,MAX_N> at{};
 for(int i=0;i<n;++i)at[beta[i]]=i;
 for(int x=0;x<n;++x)positioned[at[x]]=at[p[x]];
 return unoriented(positioned,n);
}
std::vector<unsigned> orientation_witnesses(const Perm& p,int n){
 std::vector<unsigned> result;unsigned seen=0;
 for(int first=0;first<n;++first)if(!(seen&(1u<<first))){
  unsigned mask=0;int x=first,length=0;
  do{seen|=1u<<x;mask|=1u<<x;++length;x=p[x];}while(x!=first);
  if(length>=3)result.push_back(mask);
 }return result;
}
int mask_depth(unsigned mask){return 31-__builtin_clz(mask);}
bool witness_oriented(const Perm& p,unsigned mask,const int* positions){
 int first=__builtin_ctz(mask),x=first,increases=0;
 do{increases+=positions[p[x]]>positions[x];x=p[x];}while(x!=first);
 return increases!=1;
}
bool same(Triple x,Triple y){return x.a==y.a&&x.b==y.b&&x.c==y.c;}
bool power_on_support(const Perm& p,const int* support,int length,int exponent){
 for(int i=0;i<length;++i)if(p[support[i]]!=support[(i+exponent)%length])return false;
 return true;
}
bool admitted_power(const Perm& p,const int* support,int length){
 switch(length){
  case 9:return power_on_support(p,support,length,7);
  case 11:return power_on_support(p,support,length,6);
  case 13:return power_on_support(p,support,length,7)||power_on_support(p,support,length,10)||power_on_support(p,support,length,11);
  case 17:return power_on_support(p,support,length,9)||power_on_support(p,support,length,13)||power_on_support(p,support,length,15);
  default:return false;
 }
}
int support_closing_length(const Perm& p,int n){
 unsigned seen=0;int chosen=0;
 for(int first=0;first<n;++first)if(!(seen&(1u<<first))){
  int support[MAX_N],length=0,x=first;
  do{seen|=1u<<x;support[length++]=x;x=p[x];}while(x!=first);
  if((length==9||length==11||length==13||length==17)&&(chosen==0||length<chosen)){
   std::sort(support,support+length);if(admitted_power(p,support,length))chosen=length;
  }
 }return chosen;
}
// Twisted reflection is f*p^{-1}*f, not ordinary image-array reversal.
bool canonical(const Perm& p,int n,const std::array<bool,MAX_N>& anchors){
 Perm inverse{};for(int i=0;i<n;++i)inverse[p[i]]=i;
 for(int a=0;a<n;++a)if(anchors[a])for(int reflection=0;reflection<2;++reflection)for(int j=0;j<n;++j){
  int value=reflection?(a-inverse[(a-j+n)%n]+n)%n:(p[(j+a)%n]-a+n)%n;
  if(value<p[j])return false;if(value>p[j])break;
 }
 return true;
}
struct Count{std::uint64_t nodes=0,leaves=0,unoriented_leaves=0,classes=0,no_qualifying=0,cleared=0;std::array<std::uint64_t,4> cleared_by_length{};};
class Counter{
 int n;std::vector<int> parts;Perm omega{},inverse{},position{},cycle_start{},cycle_length{},previous_equal{},target{},partial_inverse{};
 int target_edges=0;
 int shard_index=0,shard_count=1,shard_depth=1;
 std::uint64_t prefix_ordinal=0;
 std::array<std::vector<Triple>,MAX_N> fast;
 std::array<std::vector<PairConstraint>,MAX_N> pairs;
 std::array<std::vector<PathConstraint>,MAX_N> paths;
 std::array<std::vector<PairGroup>,MAX_N> grouped_pairs;
 std::array<std::vector<PathGroup>,MAX_N> grouped_paths;
 std::vector<unsigned char> cyclic_order,after_positions;
 std::vector<Triple> firsts;Count count;std::ostream* representatives;
 int triple_index(int a,int b,int c)const{return(a*n+b)*n+c;}
 bool constraints(int d)const{
  for(const auto t:fast[d])if(cyclic_order[triple_index(position[t.a],position[t.b],position[t.c])])return false;
  for(const auto& group:grouped_pairs[d]){
   int index=triple_index(position[group.first.a],position[group.first.b],position[group.first.c]);
   if(!cyclic_order[index])continue;
   const auto* transform1=after_positions.data()+index*n;
   for(const auto t:group.seconds){
    if(cyclic_order[triple_index(transform1[position[t.a]],transform1[position[t.b]],
       transform1[position[t.c]])])return false;
   }
  }
  for(const auto& group:grouped_paths[d]){
   int index=triple_index(position[group.first.a],position[group.first.b],position[group.first.c]);
   if(!cyclic_order[index])continue;
   const auto* transform1=after_positions.data()+index*n;int p1[MAX_N];
   for(int x=0;x<n;++x)p1[x]=transform1[position[x]];
   Perm q=apply_move(omega,group.first);
   for(const auto& second_group:group.seconds){const auto second=second_group.second;
    int second_index=triple_index(p1[second.a],p1[second.b],p1[second.c]);
    if(!cyclic_order[second_index])continue;
    const auto* transform2=after_positions.data()+second_index*n;int p2[MAX_N];
    for(int x=0;x<n;++x)p2[x]=transform2[p1[x]];
    for(const auto& entry:second_group.thirds){const auto third=entry.third;
     if((entry.q_witness==0||witness_oriented(q,entry.q_witness,p1))
        &&cyclic_order[triple_index(p2[third.a],p2[third.b],p2[third.c])])return false;
    }
   }
  }return true;
 }
 bool add_target(int rank){
  int source=(position[rank]+n-1)%n,dest=position[omega[rank]];target[source]=dest;partial_inverse[dest]=position[rank];++target_edges;int x=dest;
  while(x!=source&&target[x]>=0)x=target[x];return x!=source||target_edges==n;
 }
 void remove_target(int rank){partial_inverse[position[omega[rank]]]=-1;target[(position[rank]+n-1)%n]=-1;--target_edges;}
 bool canonical_prefix(int d)const{
  // These are exactly the full canonical comparisons, stopped at the first
  // unknown image. Every assigned largest-cycle vertex is already a valid
  // possible origin. A strict comparison cannot be changed by completing
  // unknown images, so rejection preserves every least orbit representative.
  for(int rank=0;rank<=d;++rank)if(cycle_length[rank]==parts.front()){
   int a=position[rank];
   for(int reflection=0;reflection<2;++reflection){
    if(!reflection&&a==0)continue;
    for(int j=0;j<n;++j){
     int original=target[(j+n-1)%n];if(original<0)break;
     int image=reflection?partial_inverse[(a-j+n)%n]:target[(j+a+n-1)%n];
     if(image<0)break;
     int value=reflection?(a-image+n)%n:(image-a+n)%n;
     if(value<original)return false;if(value>original)break;
    }
   }
  }return true;
 }
 void leaf(){
  ++count.leaves;Perm p{};std::array<bool,MAX_N> anchors{};
  for(int r=0;r<n;++r){p[position[r]]=position[omega[r]];anchors[position[r]]=cycle_length[r]==parts.front();}
  if(unoriented(p,n)){++count.unoriented_leaves;return;}if(!canonical(p,n,anchors))return;
  ++count.classes;int closing=n==MAX_N?support_closing_length(p,n):0;
  if(closing){++count.cleared;int index=closing==17?3:(closing-9)/2;++count.cleared_by_length[index];}
  bool qualifying=false;
  for(const auto t:firsts)if(ordered(position[t.a],position[t.b],position[t.c],n)){qualifying=true;break;}
  if(!qualifying)++count.no_qualifying;
  if(representatives){for(int i=0;i<n;++i){if(i)*representatives<<' ';*representatives<<p[i];}*representatives<<'\n';}
 }
 void dfs(int d,unsigned used){
  // Every accepted prefix at the cut belongs to exactly one shard. Shared
  // trunk nodes are charged only to shard zero; owned subtrees are charged
  // normally. Their sums reproduce even the unsharded diagnostic counters.
  const int cut=shard_depth+1;
  if(shard_depth>1&&d==cut&&prefix_ordinal++%shard_count!=static_cast<unsigned>(shard_index))return;
  if((shard_depth==1?(d!=1||shard_index==0):(d>=cut||shard_index==0)))++count.nodes;
  if(d==n){leaf();return;}
  bool start=cycle_start[d]==d;int minimum=1;
  if(!start)minimum=position[cycle_start[d]]+1;else if(previous_equal[d]>=0)minimum=position[previous_equal[d]]+1;
  int branch=0;
  for(int x=minimum;x<n;++x)if(!(used&(1u<<x))){
   if(shard_depth==1&&d==1&&branch++%shard_count!=shard_index)continue;
   position[d]=x;
   bool edge_a=omega[d]<=d,edge_b=inverse[d]<d,okay=true,added_b=false;
   if(edge_a)okay=add_target(d);
   if(okay&&edge_b){okay=add_target(inverse[d]);added_b=true;}
   // A forced proper target cycle cannot be repaired by a later assignment.
   // Check this inexpensive condition before all two-move predicates.
   if(okay&&canonical_prefix(d)&&constraints(d))dfs(d+1,used|(1u<<x));
   if(added_b)remove_target(inverse[d]);if(edge_a)remove_target(d);
  }
 }
public:
 explicit Counter(std::vector<int> partition,std::ostream* output=nullptr):parts(std::move(partition)),representatives(output){
  std::sort(parts.rbegin(),parts.rend());n=std::accumulate(parts.begin(),parts.end(),0);
  int even_parts=std::count_if(parts.begin(),parts.end(),[](int length){return length%2==0;});
  if(n<3||n>MAX_N||parts.empty()||parts.back()<2||even_parts%2!=0)
   throw std::invalid_argument("Expected even fixed-point-free partition of 3..21 symbols");
  int start=0,previous=-1,previous_length=-1;previous_equal.fill(-1);target.fill(-1);partial_inverse.fill(-1);
  for(int length:parts){
   if(length==previous_length)previous_equal[start]=previous;
   for(int j=0;j<length;++j){omega[start+j]=start+(j+1)%length;cycle_start[start+j]=start;cycle_length[start+j]=length;}
   previous=start;previous_length=length;start+=length;
  }
  for(int x=0;x<n;++x)inverse[omega[x]]=x;
  for(int x=0;x<n;++x){int y=omega[x],z=omega[y];if(x==z)continue;if(omega[z]==x&&(x>y||x>z))continue;fast[std::max({x,y,z})].push_back({x,y,z});}
  std::vector<Triple> supports;
  for(int a=0;a<n-2;++a)for(int b=a+1;b<n-1;++b)for(int c=b+1;c<n;++c){
   supports.push_back({a,b,c});supports.push_back({a,c,b});
  }
  int odd=odd_cycles(omega,n);
  for(const auto first:supports){
   if(gain(omega,first)!=1)continue;
   Perm q=apply_move(omega,first);if(odd_cycles(q,n)!=odd+2)continue;firsts.push_back(first);
   const auto q_witnesses=orientation_witnesses(q,n);if(q_witnesses.empty())continue;
   for(const auto second:supports){
    if(q[second.a]==second.a||q[second.b]==second.b||q[second.c]==second.c)continue;
    int second_gain=gain(q,second);if(second_gain<1)continue;
    Perm r=apply_move(q,second);if(odd_cycles(r,n)!=odd_cycles(q,n)+2)continue;
    if(second_gain==3){
     int depth=std::max({first.a,first.b,first.c,second.a,second.b,second.c});
     pairs[depth].push_back({first,second});
     continue;
    }
    if(second_gain>2)continue;
    for(int x=0;x<n;++x){int y=r[x],z=r[y];
     if(x==y||x==z||y==z)continue;if(r[z]==x&&(x>y||x>z))continue;
     Triple third{x,y,z};int third_gain=gain(r,third);
     if((second_gain==1&&third_gain!=3)||(second_gain==2&&third_gain<2))continue;
     if(second_gain==2){
      int depth=std::max({first.a,first.b,first.c,second.a,second.b,second.c,x,y,z});
      paths[depth].push_back({first,second,third,0});
     }else for(unsigned qw:q_witnesses){
      int depth=std::max({first.a,first.b,first.c,second.a,second.b,second.c,x,y,z,mask_depth(qw)});
      paths[depth].push_back({first,second,third,qw});
     }
    }
   }
  }
  cyclic_order.resize(n*n*n);after_positions.resize(n*n*n*n);
  for(int a=0;a<n;++a)for(int b=0;b<n;++b)for(int c=0;c<n;++c){
   int index=triple_index(a,b,c);
   cyclic_order[index]=a!=b&&a!=c&&b!=c&&ordered(a,b,c,n);
   if(cyclic_order[index])for(int x=0;x<n;++x)after_positions[index*n+x]=after_position(x,a,b,c,n);
  }
  for(int d=0;d<n;++d)for(const auto& pair:pairs[d]){
   auto& groups=grouped_pairs[d];
   auto found=std::find_if(groups.begin(),groups.end(),[&](const auto& group){
    return same(group.first,pair.first);
   });
   if(found==groups.end()){groups.push_back({pair.first,{}});found=groups.end()-1;}
   found->seconds.push_back(pair.second);
  }
  for(int d=0;d<n;++d)for(const auto& path:paths[d]){
   auto& groups=grouped_paths[d];
   auto first=std::find_if(groups.begin(),groups.end(),[&](const auto& group){return same(group.first,path.first);});
   if(first==groups.end()){groups.push_back({path.first,{}});first=groups.end()-1;}
   auto second=std::find_if(first->seconds.begin(),first->seconds.end(),[&](const auto& group){return same(group.second,path.second);});
   if(second==first->seconds.end()){first->seconds.push_back({path.second,{}});second=first->seconds.end()-1;}
   second->thirds.push_back({path.third,path.q_witness});
  }
 }
 Count run(int selected_shard=0,int shards=1,int depth=1){
  if(shards<1||selected_shard<0||selected_shard>=shards||depth<1||depth>=MAX_N)throw std::invalid_argument("Invalid shard or depth");
  shard_index=selected_shard;shard_count=shards;shard_depth=std::min(depth,n-1);
  position[0]=0;dfs(1,1);return count;
 }

};
// Independent complete-permutation validation with arbitrary transposition
// supports, without prefix predicates or the consecutive-triple criterion.
Perm transpose_beta(const Perm& beta,int i,int j,int k,int n){
 Perm result=beta;int at=i;
 for(int x=j;x<k;++x)result[at++]=beta[x];
 for(int x=i;x<j;++x)result[at++]=beta[x];
 return result;
}
bool brute_residual(const Perm& p,int n,bool& no_qualifying){
 int initial_odd=odd_cycles(p,n);no_qualifying=true;Perm beta{};std::iota(beta.begin(),beta.begin()+n,0);
 for(int i=0;i<n-2;++i)for(int j=i+1;j<n-1;++j)for(int k=j+1;k<n;++k){
  Triple first{beta[i],beta[j],beta[k]};int first_gain=gain(p,first);
  if(first_gain>=2)return false;if(first_gain!=1)continue;
  Perm q=apply_move(p,first);if(odd_cycles(q,n)!=initial_odd+2)continue;no_qualifying=false;
  Perm beta1=transpose_beta(beta,i,j,k,n);if(totally_unoriented(q,beta1,n))continue;
  int q_odd=odd_cycles(q,n);
  for(int r0=0;r0<n-2;++r0)for(int s= r0+1;s<n-1;++s)for(int t=s+1;t<n;++t){
   Triple second{beta1[r0],beta1[s],beta1[t]};
   if(q[second.a]==second.a||q[second.b]==second.b||q[second.c]==second.c)continue;
   int second_gain=gain(q,second);if(second_gain<1)continue;Perm r=apply_move(q,second);
   if(odd_cycles(r,n)!=q_odd+2)continue;if(second_gain==3)return false;if(second_gain>2)continue;
   Perm beta2=transpose_beta(beta1,r0,s,t,n);if(totally_unoriented(r,beta2,n))continue;
   int r_odd=odd_cycles(r,n);
   for(int u=0;u<n-2;++u)for(int v=u+1;v<n-1;++v)for(int w=v+1;w<n;++w){
    Triple third{beta2[u],beta2[v],beta2[w]};
    if(r[third.a]==third.a||r[third.b]==third.b||r[third.c]==third.c)continue;
    int third_gain=gain(r,third);
    if(((second_gain==1&&third_gain==3)||(second_gain==2&&third_gain>=2))
       &&odd_cycles(apply_move(r,third),n)==r_odd+2)return false;
   }
  }
 }return true;
}
void partitions(int remainder,int maximum,std::vector<int>& prefix,std::vector<std::vector<int>>& out){
 if(remainder==0){int evens=std::count_if(prefix.begin(),prefix.end(),[](int x){return x%2==0;});if(evens%2==0)out.push_back(prefix);return;}
 for(int x=std::min(remainder,maximum);x>=2;--x){prefix.push_back(x);partitions(remainder-x,x,prefix,out);prefix.pop_back();}
}
std::vector<std::vector<int>> all_partitions(int n){std::vector<std::vector<int>> out;std::vector<int> prefix;partitions(n,n,prefix,out);return out;}
void verify_small(int maximum){
 if(maximum>10)throw std::invalid_argument("Exhaustive validation is limited to N<=10");
 for(int n=3;n<=maximum;++n){
  std::uint64_t direct=0,direct_noqual=0,constrained=0,constrained_noqual=0;Perm p{};std::iota(p.begin(),p.begin()+n,0);std::array<bool,MAX_N> anchors;anchors.fill(true);
  do{bool fixed=false;for(int x=0;x<n;++x)if(p[x]==x){fixed=true;break;}if(fixed||!full_target(p,n)||unoriented(p,n)||!canonical(p,n,anchors))continue;bool noqual=false;if(brute_residual(p,n,noqual)){++direct;direct_noqual+=noqual;}}while(std::next_permutation(p.begin(),p.begin()+n));
  for(auto part:all_partitions(n)){Count c=Counter(part).run();constrained+=c.classes;constrained_noqual+=c.no_qualifying;}
  if(direct!=constrained||direct_noqual!=constrained_noqual)throw std::runtime_error("Small-domain comparison failed at N="+std::to_string(n)+": direct="+std::to_string(direct)+", constrained="+std::to_string(constrained));
  std::cout<<"{\"format\":\"td20-joint3-small-check-v1\",\"n\":"<<n<<",\"classes\":"<<direct<<",\"no_qualifying_first_move\":"<<direct_noqual<<",\"agrees\":true}"<<std::endl;
 }
}
void verify_shards(int maximum){
 if(maximum<3||maximum>14)throw std::invalid_argument("Shard validation is limited to N=3..14");
 std::uint64_t checks=0;
 for(int n=3;n<=maximum;++n)for(const auto& part:all_partitions(n)){
  const Count baseline=Counter(part).run();
  for(int depth: {1,2,4}){
   Count sum{};
   for(int shard=0;shard<7;++shard){
    const Count row=Counter(part).run(shard,7,depth);
    sum.nodes+=row.nodes;sum.leaves+=row.leaves;sum.unoriented_leaves+=row.unoriented_leaves;
    sum.classes+=row.classes;sum.no_qualifying+=row.no_qualifying;sum.cleared+=row.cleared;
    for(int index=0;index<4;++index)sum.cleared_by_length[index]+=row.cleared_by_length[index];
   }
   if(sum.nodes!=baseline.nodes||sum.leaves!=baseline.leaves||sum.unoriented_leaves!=baseline.unoriented_leaves
      ||sum.classes!=baseline.classes||sum.no_qualifying!=baseline.no_qualifying||sum.cleared!=baseline.cleared
      ||sum.cleared_by_length!=baseline.cleared_by_length)
    throw std::runtime_error("Shard union differs from unsharded count: N="+std::to_string(n)+", depth="+std::to_string(depth));
   ++checks;
  }
 }
 std::cout<<"{\"format\":\"td20-joint3-shard-check-v1\",\"maximum_domain\":"<<maximum
  <<",\"shards\":7,\"depths\":[1,2,4],\"checks\":"<<checks<<",\"agrees\":true}"<<std::endl;
}
std::vector<int> parse_partition(std::string s){std::vector<int> out;size_t start=0;do{size_t end=s.find(',',start);out.push_back(std::stoi(s.substr(start,end-start)));if(end==std::string::npos)break;start=end+1;}while(true);return out;}

}
int main(int argc,char** argv){
 try{
  int n=21,verify=0,verify_shard_max=0,shard_index=0,shard_count=1,shard_depth=1;std::vector<int> selected;std::string representatives_path;
  for(int i=1;i<argc;++i){std::string arg=argv[i];if(arg.starts_with("--n="))n=std::stoi(arg.substr(4));else if(arg.starts_with("--partition="))selected=parse_partition(arg.substr(12));else if(arg.starts_with("--verify-small="))verify=std::stoi(arg.substr(15));else if(arg.starts_with("--verify-shards="))verify_shard_max=std::stoi(arg.substr(16));else if(arg.starts_with("--representatives="))representatives_path=arg.substr(18);else if(arg.starts_with("--shard-index="))shard_index=std::stoi(arg.substr(14));else if(arg.starts_with("--shard-count="))shard_count=std::stoi(arg.substr(14));else if(arg.starts_with("--shard-depth="))shard_depth=std::stoi(arg.substr(14));else throw std::invalid_argument("Unknown argument: "+arg);}
  if(verify){verify_small(verify);return 0;}if(verify_shard_max){verify_shards(verify_shard_max);return 0;}if(n<3||n>MAX_N)throw std::invalid_argument("N must be 3..21");
  auto parts=selected.empty()?all_partitions(n):std::vector<std::vector<int>>{selected};
  if(!selected.empty())n=std::accumulate(selected.begin(),selected.end(),0);
  std::ofstream reps;if(!representatives_path.empty()){reps.open(representatives_path);if(!reps)throw std::runtime_error("Cannot open representatives output");reps<<"TD20-JOINT3-RESIDUALS 1 N="<<n<<" scope=oriented\n";}
  std::uint64_t total=0;
  for(const auto& part:parts){
   auto started=std::chrono::steady_clock::now();Count c=Counter(part,reps.is_open()?&reps:nullptr).run(shard_index,shard_count,shard_depth);total+=c.classes;
   double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count();
   std::cout<<"{\"format\":\"td20-joint3-residual-count-v2\",\"partition\":[";for(size_t i=0;i<part.size();++i){if(i)std::cout<<',';std::cout<<part[i];}
   std::cout<<"],\"shard_index\":"<<shard_index<<",\"shard_count\":"<<shard_count
    <<",\"shard_depth\":"<<std::min(shard_depth,n-1)
    <<",\"sharding\":\""<<(shard_depth==1?"first-position-modulo-v1":"accepted-prefix-modulo-v2")<<"\""
    <<",\"oriented_root_classes\":"<<c.classes
    <<",\"support_clearing_classes\":"<<c.cleared
    <<",\"oriented_after_support_clearing\":"<<c.classes-c.cleared
    <<",\"support_clearing_by_least_length\":{\"9\":"<<c.cleared_by_length[0]
    <<",\"11\":"<<c.cleared_by_length[1]<<",\"13\":"<<c.cleared_by_length[2]
    <<",\"17\":"<<c.cleared_by_length[3]<<"},\"no_qualifying_first_move\":"<<c.no_qualifying
    <<",\"qualifying_but_no_favorable_prefix\":"<<c.classes-c.no_qualifying
    <<",\"prefix_nodes\":"<<c.nodes<<",\"realizable_leaves\":"<<c.leaves
    <<",\"ignored_unoriented_leaves\":"<<c.unoriented_leaves<<",\"elapsed_seconds\":"<<elapsed<<"}"<<std::endl;
  }
  if(reps.is_open()){reps<<"COUNT "<<total<<'\n';reps.close();if(!reps)throw std::runtime_error("Representatives output failed");}
 }catch(const std::exception& e){std::cerr<<e.what()<<std::endl;return 1;}
}
